// SoftSchool WhatsApp gateway — one Baileys session PER SCHOOL (keyed by schoolId, e.g. "SS_77_5").
// Only the Spring Boot backend talks to this service (shared API key, bind to localhost).
//
// Ban protection (see safety.js): every school number has its own queue that drains slowly and
// "like a person" — random gaps, typing time, rests between batches, the school's own daily limit,
// a per-parent daily limit, a duplicate-text guard, and a pause after repeated errors.
// A single message is sent immediately. Bulk requests (2+ messages) and messages that follow another one
// within a short time ("in a row") wait in the queue (saved to disk, so a restart does not lose them) and
// are delivered a little late instead. There is no time-of-day restriction.
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
  minDelay: num(process.env.MIN_DELAY_MS, 8000),    // gap between two messages from one number (random 8-16 s)
  maxDelay: num(process.env.MAX_DELAY_MS, 16000),
  bulkFrom: num(process.env.BULK_FROM, 10),          // this many waiting messages = "bulk": extra human-like randomness kicks in
  maxQueue: num(process.env.MAX_QUEUE, 5000),        // per school; beyond this new messages are refused (429)
  rowGapMs: num(process.env.DIRECT_ROW_GAP_MS, 30_000), // a single message within this time of the previous send counts as "in a row" -> queued
  maxAgeMs: num(process.env.MAX_QUEUE_AGE_HOURS, 72) * 60 * MIN, // a message still waiting after this is dropped (too stale to be useful)
  maxPerRequest: num(process.env.MAX_PER_REQUEST, 500),
  maxText: num(process.env.MAX_TEXT_LENGTH, 1500),   // very long messages look like spam
  apiPerMinute: num(process.env.API_RATE_PER_MIN, 1500), // generous: generating vouchers for a whole school makes one call per student
  guard: {
    batchSize: num(process.env.BATCH_SIZE, 15),
    batchPauseMin: num(process.env.BATCH_PAUSE_MIN_MS, 3 * MIN),
    batchPauseMax: num(process.env.BATCH_PAUSE_MAX_MS, 7 * MIN),
    perRecipientDaily: num(process.env.PER_RECIPIENT_DAILY, 4),
    tz: process.env.SEND_TZ || 'Asia/Karachi',
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
function normalizePhone(raw, country = cfg.cc, strict = false) {
  let d = String(raw || '').replace(/[^\d]/g, '');
  if (!d) return null;
  if (d.startsWith('00')) d = d.slice(2);
  else if (d.startsWith('0')) d = country + d.slice(1);
  else if (!d.startsWith(country) && d.length <= 10) d = country + d;
  if (d.length < 10 || d.length > 15) return null;
  return strict && looksFakeNumber(d, country) ? null : d;
}

/** Numbers nobody really owns: 11111, 0300-0000000, 03111111111, 03001234567 ... and non-mobile Pakistani numbers. */
function looksFakeNumber(d, country = cfg.cc) {
  const n = d.startsWith(country) ? d.slice(country.length) : d;
  if (country === '92' && d.startsWith('92') && !/^3\d{9}$/.test(n)) return true; // PK mobile = 3XX XXXXXXX
  if (new Set(n).size <= 2) return true;                                        // 0000000000, 3111111111
  const tail = n.slice(-7);
  if (new Set(tail).size === 1) return true;                                    // 0300-1111111
  return '01234567890123456789'.includes(tail) || '98765432109876543210'.includes(tail); // 0300-1234567
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
    this.lastSendAt = 0;      // when the last message (direct or queued) went out
    this.directBusy = false;  // true while a single immediate send is in progress
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
        this.freshLink = false;
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
      waiting: busy ? (g.pausedReason || this.waitReason) : null, // why the queue is idle: cooldown | daily_limit | errors | ...
      ...g,
    };
  }

  /** Linked numbers keep accepting messages during a short reconnect; they go out once it is back. */
  canQueue() { return this.state === 'connected' || (this.state === 'connecting' && !!this.me); }

  /** Adds messages to this school's queue. Nothing is sent from here: pump() drains it at a safe pace. */
  enqueue(items, country) {
    const queuedKeys = new Set(this.queue.map((m) => m.to + '\n' + m.text));
    // A big batch (fee vouchers for a whole class, absent alerts ...) is NOT sent in roll-number order:
    // a strictly ordered run to one class looks like a script. Shuffle it, but give every result back in
    // the caller's original order (the backend matches results by index).
    const order = items.map((_, i) => i);
    if (items.length >= cfg.bulkFrom) {
      for (let i = order.length - 1; i > 0; i--) { const j = rand(0, i); [order[i], order[j]] = [order[j], order[i]]; }
    }
    const results = new Array(items.length);
    for (const idx of order) {
      const it = items[idx];
      const to = normalizePhone(it?.to, country, true);
      if (!to) { results[idx] = { to: it?.to, accepted: false, reason: 'invalid number' }; continue; }
      const text = String(it?.text ?? '').trim().slice(0, cfg.maxText);
      if (!text) { results[idx] = { to, accepted: false, reason: 'empty message' }; continue; }
      const key = to + '\n' + text;
      if (queuedKeys.has(key)) { results[idx] = { to, accepted: false, reason: 'duplicate already queued' }; continue; }
      queuedKeys.add(key);
      this.queue.push({ to, text, at: Date.now() });
      results[idx] = { to, accepted: true };
    }
    this.persistSoon();
    this.pump();
    return results;
  }

  /** True when this request may skip the queue: exactly one message, nothing waiting, not "in a row", guard happy. */
  canSendDirect(items, bulk = false) {
    if (bulk || items.length !== 1) return false;
    if (this.state !== 'connected' || !this.sock) return false;
    if (this.queue.length || this.working || this.directBusy) return false;
    if (Date.now() - this.lastSendAt < cfg.rowGapMs) return false;   // sent something a moment ago => in a row
    return this.guard.check().ok;
  }

  /**
   * Entry point for /send. Single message -> delivered right now (still typing-delay + the daily / per-parent limits).
   * Bulk / in a row / guard says wait -> goes to the queue and is delivered at a safe pace.
   */
  async submit(items, country, bulk = false) {
    if (!this.canSendDirect(items, bulk)) return { mode: 'queue', results: this.enqueue(items, country) };

    const it = items[0];
    const to = normalizePhone(it?.to, country, true);
    if (!to) return { mode: 'direct', results: [{ to: it?.to, accepted: false, reason: 'invalid number' }] };
    const text = String(it?.text ?? '').trim().slice(0, cfg.maxText);
    if (!text) return { mode: 'direct', results: [{ to, accepted: false, reason: 'empty message' }] };
    // per-parent daily cap / duplicate text: same protection as the queue gives
    const rule = this.guard.canSendTo(to, text);
    if (!rule.ok) return { mode: 'direct', results: [{ to, accepted: false, reason: rule.reason }] };

    this.directBusy = true;
    this.lastSendAt = Date.now(); // claim the slot so a second request right behind this one is queued
    try {
      const jid = await this.resolve(to);
      if (!jid) return { mode: 'direct', results: [{ to, accepted: false, reason: 'number not on WhatsApp' }] };
      await this.sock.sendPresenceUpdate('composing', jid);
      await sleep(Math.min(3000, Math.max(800, text.length * 20)) + rand(200, 800));
      await this.sock.sendMessage(jid, { text });
      try { await this.sock.sendPresenceUpdate('paused', jid); } catch { /* noop */ }
      this.guard.recordSent(to, text);
      this.lastSendAt = Date.now();
      this.persistSoon();
      return { mode: 'direct', results: [{ to, accepted: true, sent: true }] };
    } catch (e) {
      log.warn({ school: this.schoolId, e: e.message }, 'direct send failed');
      if (this.state !== 'connected') return { mode: 'queue', results: this.enqueue(items, country) }; // link dropped: retry after reconnect
      this.guard.recordFailure();
      this.persistSoon();
      return { mode: 'direct', results: [{ to, accepted: false, reason: 'send failed' }] };
    } finally {
      this.directBusy = false;
      if (this.queue.length) this.pump();
    }
  }

  /** Re-checks the limits later (rest, daily limit ...) and carries on by itself. */
  wakeIn(ms, reason) {
    this.waitReason = reason;
    clearTimeout(this.wakeTimer);
    this.wakeTimer = setTimeout(() => { this.wakeTimer = null; this.pump(); }, Math.max(1000, ms));
    log.info({ school: this.schoolId, reason, minutes: Math.round(ms / 6000) / 10, queued: this.queue.length }, 'queue waiting');
  }

  /** One drain loop per school. Sends at most one message at a time, always asking the guard first. */
  async pump() {
    if (this.working || this.state !== 'connected') return;
    if (this.directBusy) { this.wakeIn(2000, 'busy'); return; } // a single immediate send is in progress
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

        // keep a natural gap after the previous message, even if that one was a direct send
        const since = Date.now() - this.lastSendAt;
        if (since < cfg.minDelay) await sleep(cfg.minDelay - since + rand(0, 2000));

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
            this.lastSendAt = Date.now();
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
        // Random gap between messages: always 8-16 s (MIN/MAX_DELAY_MS). For a big queue (fee vouchers for a
        // whole school, 10+ waiting) the pattern is made less regular still, because a steady rhythm is what
        // automation detection looks for: sometimes a quick follow-up, sometimes a longer think, and an
        // occasional "got distracted" pause. Every school has its own loop, so none of this slows another school.
        let gap = rand(cfg.minDelay, cfg.maxDelay);
        if (sent && this.queue.length >= cfg.bulkFrom) {
          const r = Math.random();
          if (r < 0.15) gap = cfg.minDelay + rand(0, 2000);                       // quick follow-up
          else if (r < 0.30) gap = cfg.maxDelay - rand(0, 2000);                  // slower one
          if (Math.random() < 0.08) gap += rand(15_000, 45_000);                  // distracted for a moment
        } else if (sent && Math.random() < 0.1) gap += rand(20_000, 60_000);
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

// The school's own daily sending limit from Settings. 0 / null = no daily limit.
// body: { dailyLimit: 150 }   (null / 0 = no daily limit)
app.put('/sessions/:schoolId/limits', (req, res) => {
  const s = getSession(req.params.schoolId, true);
  if (!s) return res.status(400).json({ error: 'bad schoolId' });
  s.guard.setUserLimit(req.body?.dailyLimit);
  s.persistSoon();
  res.json(s.view());
});

// body: { messages: [{ to, text }], country?, dailyLimit? }   (single send = one item)
// A single message (nothing waiting, not right behind another one) is sent immediately.
// Bulk requests (2+ messages), `bulk: true`, or messages in a row are QUEUED and delivered at a safe pace.
app.post('/sessions/:schoolId/send', async (req, res) => {
  const s = getSession(req.params.schoolId);
  if (!s || !s.canQueue()) return res.status(409).json({ error: 'whatsapp not connected' });
  const items = Array.isArray(req.body?.messages) ? req.body.messages : [];
  if (!items.length || items.length > cfg.maxPerRequest) {
    return res.status(400).json({ error: `1..${cfg.maxPerRequest} messages required per request` });
  }
  if (req.body?.dailyLimit !== undefined) s.guard.setUserLimit(req.body.dailyLimit);
  const bulk = req.body?.bulk === true;
  if (!s.canSendDirect(items, bulk) && s.queue.length + items.length > cfg.maxQueue) {
    return res.status(429).set('Retry-After', '600').json({
      error: 'queue is full for this number, try again later', queued: s.queue.length, maxQueue: cfg.maxQueue,
    });
  }
  const { mode, results } = await s.submit(items, req.body?.country, bulk);
  res.json({ mode, results, queued: s.queue.length, etaMinutes: s.guard.etaMinutes(s.queue.length, cfg.minDelay, cfg.maxDelay) });
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
