// SoftSchool WhatsApp gateway — one Baileys session PER SCHOOL (keyed by schoolId, e.g. "SS_77_5").
// Only the Spring Boot backend talks to this service (shared API key, bind to localhost).
//
// Ban protection (see safety.js): every school number has its own queue that drains slowly and
// "like a person" — random gaps, typing time, rests between batches, hourly/daily caps, quiet hours,
// a slow warm-up for newly linked numbers, per-parent and duplicate limits, and a pause after errors.
// Messages are never refused for being too many; they wait in the queue (saved to disk, so a restart
// does not lose them) and are delivered a little late instead.
import fs from 'node:fs';
import path from 'node:path';
import express from 'express';
import pino from 'pino';
import QRCode from 'qrcode';
import makeWASocket, {
  useMultiFileAuthState,
  DisconnectReason,
  fetchLatestBaileysVersion,
  Browsers,
} from '@whiskeysockets/baileys';
import { SendGuard } from './safety.js';

const MIN = 60_000;
const num = (v, d) => (v !== undefined && v !== '' && Number.isFinite(+v) ? +v : d);

const cfg = {
  port: num(process.env.PORT, 3001),
  host: process.env.HOST || '127.0.0.1',
  apiKey: process.env.API_KEY || '',
  sessionsDir: path.resolve(process.env.SESSIONS_DIR || './sessions'),
  cc: String(process.env.DEFAULT_COUNTRY_CODE || '92').replace(/\D/g, ''),
  minDelay: num(process.env.MIN_DELAY_MS, 8000),    // gap between two messages from one number
  maxDelay: num(process.env.MAX_DELAY_MS, 20000),
  maxQueue: num(process.env.MAX_QUEUE, 5000),        // per school; beyond this new messages are refused (429)
  maxAgeMs: num(process.env.MAX_QUEUE_AGE_HOURS, 72) * 60 * MIN, // a message still waiting after this is dropped (too stale to be useful)
  maxPerRequest: num(process.env.MAX_PER_REQUEST, 500),
  maxText: num(process.env.MAX_TEXT_LENGTH, 1500),   // very long messages look like spam
  apiPerMinute: num(process.env.API_RATE_PER_MIN, 1500), // generous: generating vouchers for a whole school makes one call per student
  guard: {
    dailyLimit: num(process.env.DAILY_LIMIT, 250),
    hourlyLimit: num(process.env.HOURLY_LIMIT, 40),
    batchSize: num(process.env.BATCH_SIZE, 15),
    batchPauseMin: num(process.env.BATCH_PAUSE_MIN_MS, 3 * MIN),
    batchPauseMax: num(process.env.BATCH_PAUSE_MAX_MS, 7 * MIN),
    perRecipientDaily: num(process.env.PER_RECIPIENT_DAILY, 3),
    windowStart: num(process.env.SEND_WINDOW_START, 8),   // 0 and 24 = send around the clock
    windowEnd: num(process.env.SEND_WINDOW_END, 21),
    tz: process.env.SEND_TZ || 'Asia/Karachi',
    warmupDays: num(process.env.WARMUP_DAYS, 7),
    warmupStart: num(process.env.WARMUP_START, 30),
  },
};
if (!cfg.apiKey || cfg.apiKey === 'change-me') {
  console.error('FATAL: set a strong API_KEY in .env');
  process.exit(1);
}
if (cfg.maxDelay < cfg.minDelay) cfg.maxDelay = cfg.minDelay;
fs.mkdirSync(cfg.sessionsDir, { recursive: true });
const log = pino({ level: process.env.LOG_LEVEL || 'info' });

/** schoolId -> Session */
const sessions = new Map();
const safeId = (id) => String(id || '').replace(/[^A-Za-z0-9_-]/g, '');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (a, b) => a + Math.floor(Math.random() * (b - a + 1));

/** "0300-1234567" | "+92 300 1234567" | "923001234567" -> "923001234567" (or null) */
function normalizePhone(raw, country = cfg.cc) {
  let d = String(raw || '').replace(/[^\d]/g, '');
  if (!d) return null;
  if (d.startsWith('00')) d = d.slice(2);
  else if (d.startsWith('0')) d = country + d.slice(1);
  else if (!d.startsWith(country) && d.length <= 10) d = country + d;
  return d.length >= 10 && d.length <= 15 ? d : null;
}

// Look up the current WhatsApp Web version at most once an hour, with a timeout. Doing it on every
// reconnect made each retry slow (and a hung request left the session stuck on "connecting").
let waVersion = null;
let waVersionAt = 0;
async function getWaVersion() {
  if (waVersion && Date.now() - waVersionAt < 3600_000) return waVersion;
  try {
    const r = await Promise.race([
      fetchLatestBaileysVersion(),
      new Promise((_, rej) => setTimeout(() => rej(new Error('version lookup timeout')), 5000)),
    ]);
    waVersion = r.version; waVersionAt = Date.now();
  } catch { /* keep the previous one, or let Baileys use its bundled default */ }
  return waVersion || undefined;
}

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return fallback; }
}

class Session {
  constructor(schoolId) {
    this.schoolId = schoolId;
    this.dir = path.join(cfg.sessionsDir, schoolId);
    this.sock = null;
    this.state = 'disconnected'; // disconnected | connecting | qr | pairing | connected
    this.qr = null;
    this.pairingCode = null;
    this.pairPhone = null;
    this.me = null;
    this.working = false;
    this.stopping = false;
    this.retries = 0;
    this.starting = false;   // true while start() is building a socket (blocks overlapping starts)
    this.retryTimer = null;  // pending auto-reconnect
    this.wakeTimer = null;   // pending "try sending again" after a rest / limit
    this.saveTimer = null;
    this.freshLink = false;  // true when this start() is a brand-new link (QR/pairing), not a restore
    this.waCache = new Map(); // number -> { jid, exists, at }   (avoid re-checking the same number)
    this.waitReason = null;
    this.guard = new SendGuard(cfg.guard, readJson(path.join(this.dir, 'guard.json'), {}));
    const q = readJson(path.join(this.dir, 'queue.json'), []);
    this.queue = Array.isArray(q) ? q : [];
  }

  // ───────── persistence (queue + counters survive a restart) ─────────
  persistSoon() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => { this.saveTimer = null; this.persist(); }, 1000);
  }
  persist() {
    try {
      if (!fs.existsSync(this.dir)) return; // wiped by logout: do not recreate
      fs.writeFileSync(path.join(this.dir, 'queue.json'), JSON.stringify(this.queue));
      fs.writeFileSync(path.join(this.dir, 'guard.json'), JSON.stringify(this.guard));
    } catch (e) { log.warn({ school: this.schoolId, e: e.message }, 'could not save queue'); }
  }

  /** Detach and close the current socket so a stale one can never fire events or fight the new one. */
  killSocket() {
    const old = this.sock;
    this.sock = null;
    if (!old) return;
    try { old.ev.removeAllListeners('connection.update'); } catch { /* noop */ }
    try { old.ev.removeAllListeners('creds.update'); } catch { /* noop */ }
    try { old.end(undefined); } catch { /* noop */ }
  }

  scheduleReconnect(wait) {
    clearTimeout(this.retryTimer);
    this.retryTimer = setTimeout(() => this.start().catch((e) => {
      log.error({ school: this.schoolId, e: e.message }, 'reconnect failed');
      this.scheduleReconnect(Math.min(30000, 2000 * ++this.retries));
    }), wait);
  }

  async start() {
    // Never run two starts at once, and drop any pending auto-reconnect: two live sockets on the same
    // credentials make WhatsApp kick one of them ("connection replaced") and they then fight forever,
    // which is exactly the "connects, disconnects, connects again after a few seconds" symptom.
    if (this.starting) return;
    this.starting = true;
    try {
      clearTimeout(this.retryTimer);
      this.stopping = false;
      this.killSocket();
      fs.mkdirSync(this.dir, { recursive: true });
      const { state, saveCreds } = await useMultiFileAuthState(this.dir);
      this.freshLink = !state.creds.registered;
      const version = await getWaVersion();
      this.state = 'connecting';
      const sock = makeWASocket({
        version,
        auth: state,
        logger: pino({ level: 'silent' }),
        browser: Browsers.appropriate('SoftSchool'),
        printQRInTerminal: false,
        markOnlineOnConnect: false,
        syncFullHistory: false,
        keepAliveIntervalMs: 20000,   // regular pings keep NAT/proxy/WhatsApp from dropping an idle link
        connectTimeoutMs: 60000,
        retryRequestDelayMs: 250,
      });
      this.sock = sock;
      sock.ev.on('creds.update', saveCreds);
      this.bind(sock);
    } finally { this.starting = false; }
  }

  bind(sock) {
    sock.ev.on('connection.update', async (u) => {
      if (this.sock !== sock) return; // event from a socket that has already been replaced
      const { connection, lastDisconnect, qr } = u;
      if (qr) {
        if (this.pairPhone && !sock.authState.creds.registered) {
          try {
            this.pairingCode = await sock.requestPairingCode(this.pairPhone);
            this.state = 'pairing';
          } catch (e) { log.warn({ e: e.message }, 'pairing code failed'); }
        } else {
          this.qr = await QRCode.toDataURL(qr, { margin: 1, width: 300 });
          this.state = 'qr';
        }
      }
      if (connection === 'open') {
        this.state = 'connected';
        this.qr = null; this.pairingCode = null; this.pairPhone = null; this.retries = 0;
        this.me = (sock.user?.id || '').split(':')[0].split('@')[0] || null;
        // A number linked just now is "new" to WhatsApp: start slowly (warm-up). Numbers that were already
        // linked before this version are left alone so upgrading never throttles an established school.
        if (this.freshLink) { this.guard.markLinked(); this.freshLink = false; this.persistSoon(); }
        log.info({ school: this.schoolId, me: this.me }, 'WhatsApp connected');
        this.pump();
      }
      if (connection === 'close') {
        const code = lastDisconnect?.error?.output?.statusCode;
        this.qr = null; this.pairingCode = null;
        if (this.stopping) { this.state = 'disconnected'; return; }
        if (code === DisconnectReason.loggedOut) {
          // School removed the linked device from their phone -> wipe creds, they must re-link.
          log.warn({ school: this.schoolId }, 'logged out from phone');
          this.state = 'disconnected'; this.me = null; this.queue = [];
          clearTimeout(this.wakeTimer);
          fs.rmSync(this.dir, { recursive: true, force: true });
          this.guard = new SendGuard(cfg.guard, {});
          return;
        }
        this.state = 'connecting';
        let wait;
        if (code === DisconnectReason.restartRequired) {
          wait = 500; // normal right after scanning the QR: WhatsApp asks for one clean restart
        } else if (code === DisconnectReason.connectionReplaced) {
          // Another process is using the SAME linked session (e.g. this service running twice, or a
          // copy of the sessions folder on another machine). Retrying fast just starts a tug-of-war.
          wait = 60000;
          log.warn({ school: this.schoolId }, 'connection replaced: is another copy of this service using the same sessions folder?');
        } else {
          wait = Math.min(30000, 2000 * ++this.retries) + rand(0, 1000);
        }
        log.info({ school: this.schoolId, code, wait }, 'reconnecting');
        this.killSocket();
        this.scheduleReconnect(wait);
      }
    });
  }

  view() {
    const g = this.guard.snapshot();
    const busy = this.queue.length > 0;
    return {
      schoolId: this.schoolId,
      // A number that is already linked and only briefly reconnecting is reported as "reconnecting",
      // so the Settings page keeps showing it as linked instead of flipping back to the QR screen.
      state: this.state === 'connecting' && this.me ? 'reconnecting' : this.state,
      connected: this.state === 'connected',
      number: this.me,
      qr: this.qr,
      pairingCode: this.pairingCode,
      queued: this.queue.length,
      etaMinutes: this.guard.etaMinutes(this.queue.length, cfg.minDelay, cfg.maxDelay),
      waiting: busy ? (g.pausedReason || this.waitReason) : null, // why the queue is idle: cooldown | hourly_limit | ...
      ...g,
    };
  }

  /** Linked numbers keep accepting messages during a short reconnect; they go out once it is back. */
  canQueue() { return this.state === 'connected' || (this.state === 'connecting' && !!this.me); }

  /** Adds messages to this school's queue. Nothing is sent from here: pump() drains it at a safe pace. */
  enqueue(items, country) {
    const out = [];
    const queuedKeys = new Set(this.queue.map((m) => m.to + '\n' + m.text));
    for (const it of items) {
      const to = normalizePhone(it?.to, country);
      if (!to) { out.push({ to: it?.to, accepted: false, reason: 'invalid number' }); continue; }
      const text = String(it?.text ?? '').trim().slice(0, cfg.maxText);
      if (!text) { out.push({ to, accepted: false, reason: 'empty message' }); continue; }
      const key = to + '\n' + text;
      if (queuedKeys.has(key)) { out.push({ to, accepted: false, reason: 'duplicate already queued' }); continue; }
      queuedKeys.add(key);
      this.queue.push({ to, text, at: Date.now() });
      out.push({ to, accepted: true });
    }
    this.persistSoon();
    this.pump();
    return out;
  }

  /** Re-checks the limits later (rest, hourly cap, quiet hours ...) and carries on by itself. */
  wakeIn(ms, reason) {
    this.waitReason = reason;
    clearTimeout(this.wakeTimer);
    this.wakeTimer = setTimeout(() => { this.wakeTimer = null; this.pump(); }, Math.max(1000, ms));
    log.info({ school: this.schoolId, reason, minutes: Math.round(ms / 6000) / 10, queued: this.queue.length }, 'queue waiting');
  }

  /** One drain loop per school. Sends at most one message at a time, always asking the guard first. */
  async pump() {
    if (this.working || this.state !== 'connected') return;
    this.working = true;
    try {
      while (this.queue.length && this.state === 'connected') {
        const c = this.guard.check();
        if (!c.ok) { this.wakeIn(c.waitMs, c.reason); break; }
        this.waitReason = null;

        const m = this.queue[0];
        if (m.at && Date.now() - m.at > cfg.maxAgeMs) {
          log.info({ school: this.schoolId, to: m.to }, 'message dropped: waited too long in the queue');
          this.queue.shift(); this.persistSoon();
          continue;
        }
        const rule = this.guard.canSendTo(m.to, m.text);
        if (!rule.ok) {
          log.info({ school: this.schoolId, to: m.to, reason: rule.reason }, 'message skipped to protect the number');
          this.queue.shift(); this.persistSoon();
          continue;
        }

        let sent = false;
        try {
          const jid = await this.resolve(m.to);
          if (jid) {
            // Look human: "typing…" for a time that grows with the message length, then send.
            await this.sock.sendPresenceUpdate('composing', jid);
            await sleep(Math.min(6000, Math.max(1200, m.text.length * 35)) + rand(300, 1500));
            await this.sock.sendMessage(jid, { text: m.text });
            try { await this.sock.sendPresenceUpdate('paused', jid); } catch { /* noop */ }
            this.guard.recordSent(m.to, m.text);
            sent = true;
          } else {
            log.info({ to: m.to }, 'number is not on WhatsApp, skipped');
          }
          this.queue.shift();
        } catch (e) {
          log.warn({ e: e.message }, 'send failed');
          if (this.state !== 'connected') break; // link dropped mid-send: keep the message, retry after reconnect
          this.queue.shift(); // do not loop forever on a bad message
          this.guard.recordFailure();
        }
        this.persistSoon();
        if (!this.queue.length) break;
        // Random gap between messages, with an occasional longer "got distracted" pause.
        let gap = rand(cfg.minDelay, cfg.maxDelay);
        if (sent && Math.random() < 0.1) gap += rand(20_000, 60_000);
        await sleep(gap);
      }
    } finally { this.working = false; }
  }

  /** number -> WhatsApp jid (or null when the number has no WhatsApp). Cached to avoid repeated lookups. */
  async resolve(to) {
    const hit = this.waCache.get(to);
    if (hit && Date.now() - hit.at < 7 * 24 * 3600_000) return hit.exists ? hit.jid : null;
    const [res] = (await this.sock.onWhatsApp(to)) || [];
    this.waCache.set(to, { jid: res?.jid, exists: !!res?.exists, at: Date.now() });
    return res?.exists ? res.jid : null;
  }

  async logout() {
    this.stopping = true;
    clearTimeout(this.retryTimer);
    clearTimeout(this.wakeTimer);
    clearTimeout(this.saveTimer); this.saveTimer = null;
    this.queue = [];
    try { await this.sock?.logout(); } catch { /* already closed */ }
    this.killSocket();
    fs.rmSync(this.dir, { recursive: true, force: true });
    this.guard = new SendGuard(cfg.guard, {});
    this.state = 'disconnected'; this.me = null; this.qr = null; this.pairingCode = null;
  }
}

function getSession(schoolId, create = false) {
  const id = safeId(schoolId);
  if (!id) return null;
  if (!sessions.has(id) && create) sessions.set(id, new Session(id));
  return sessions.get(id) || null;
}

// ───────────────────────────── HTTP API ─────────────────────────────
const app = express();
app.disable('x-powered-by');
app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => {
  if (req.get('x-api-key') !== cfg.apiKey) return res.status(401).json({ error: 'unauthorized' });
  next();
});

// Coarse request-rate guard: a runaway client or loop on the backend cannot flood the gateway.
let hits = 0;
setInterval(() => { hits = 0; }, MIN).unref();
app.use((_req, res, next) => {
  if (++hits > cfg.apiPerMinute) return res.status(429).set('Retry-After', '30').json({ error: 'too many requests, slow down' });
  next();
});

app.get('/health', (_q, res) => res.json({ ok: true, sessions: sessions.size }));

// Start linking. body: { phone?: "923001234567" }  -> phone given = pairing-code mode, else QR mode
app.post('/sessions/:schoolId/connect', async (req, res) => {
  const s = getSession(req.params.schoolId, true);
  if (!s) return res.status(400).json({ error: 'bad schoolId' });
  if (s.state === 'connected') return res.json(s.view());
  s.pairPhone = req.body?.phone ? normalizePhone(req.body.phone, req.body.country) : null;
  s.qr = null; s.pairingCode = null;
  // start() itself closes any previous socket and ignores its late events, so no manual teardown here.
  try { await s.start(); } catch (e) { return res.status(500).json({ error: e.message }); }
  res.json(s.view());
});

app.get('/sessions/:schoolId/status', (req, res) => {
  const s = getSession(req.params.schoolId);
  res.json(s ? s.view() : { schoolId: safeId(req.params.schoolId), state: 'disconnected', connected: false });
});

app.post('/sessions/:schoolId/logout', async (req, res) => {
  const s = getSession(req.params.schoolId);
  if (s) await s.logout();
  res.json({ ok: true });
});

// The school's own daily sending limit from Settings. It can only LOWER the server ceiling.
// body: { dailyLimit: 150 }   (null / 0 = use the server default)
app.put('/sessions/:schoolId/limits', (req, res) => {
  const s = getSession(req.params.schoolId, true);
  if (!s) return res.status(400).json({ error: 'bad schoolId' });
  s.guard.setUserLimit(req.body?.dailyLimit);
  s.persistSoon();
  res.json(s.view());
});

// body: { messages: [{ to, text }], country?, dailyLimit? }   (single send = one item)
// Always answers fast: messages are only QUEUED here and delivered later at a safe pace.
app.post('/sessions/:schoolId/send', (req, res) => {
  const s = getSession(req.params.schoolId);
  if (!s || !s.canQueue()) return res.status(409).json({ error: 'whatsapp not connected' });
  const items = Array.isArray(req.body?.messages) ? req.body.messages : [];
  if (!items.length || items.length > cfg.maxPerRequest) {
    return res.status(400).json({ error: `1..${cfg.maxPerRequest} messages required per request` });
  }
  if (req.body?.dailyLimit !== undefined) s.guard.setUserLimit(req.body.dailyLimit);
  if (s.queue.length + items.length > cfg.maxQueue) {
    return res.status(429).set('Retry-After', '600').json({
      error: 'queue is full for this number, try again later', queued: s.queue.length, maxQueue: cfg.maxQueue,
    });
  }
  const results = s.enqueue(items, req.body?.country);
  res.json({ results, queued: s.queue.length, etaMinutes: s.guard.etaMinutes(s.queue.length, cfg.minDelay, cfg.maxDelay) });
});

// A stray rejection inside Baileys must never kill the whole process: a crash drops EVERY school's
// WhatsApp at once and, under pm2/systemd, shows up as everything disconnecting and reconnecting.
process.on('unhandledRejection', (e) => log.error({ e: e?.message || String(e) }, 'unhandledRejection'));
process.on('uncaughtException', (e) => log.error({ e: e?.message || String(e) }, 'uncaughtException'));

// Save every queue on a clean shutdown so waiting messages are still there after a restart.
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => { for (const s of sessions.values()) s.persist(); process.exit(0); });
}

// Restore every previously linked school on boot (no QR needed again); their saved queue resumes by itself.
for (const dir of fs.readdirSync(cfg.sessionsDir)) {
  if (!fs.existsSync(path.join(cfg.sessionsDir, dir, 'creds.json'))) continue;
  getSession(dir, true).start().catch((e) => log.error(e, 'restore failed'));
}

app.listen(cfg.port, cfg.host, () => log.info(`WhatsApp service on ${cfg.host}:${cfg.port}`));
