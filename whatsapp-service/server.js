// SoftSchool WhatsApp gateway — one Baileys session PER SCHOOL (keyed by schoolId, e.g. "SS_77_5").
// Only the Spring Boot backend talks to this service (shared API key, bind to localhost).
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

const cfg = {
  port: +(process.env.PORT || 3001),
  host: process.env.HOST || '127.0.0.1',
  apiKey: process.env.API_KEY || '',
  sessionsDir: path.resolve(process.env.SESSIONS_DIR || './sessions'),
  cc: String(process.env.DEFAULT_COUNTRY_CODE || '92').replace(/\D/g, ''),
  minDelay: +(process.env.MIN_DELAY_MS || 4000),
  maxDelay: +(process.env.MAX_DELAY_MS || 9000),
  dailyLimit: +(process.env.DAILY_LIMIT || 400),
};
if (!cfg.apiKey || cfg.apiKey === 'change-me') {
  console.error('FATAL: set a strong API_KEY in .env');
  process.exit(1);
}
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
    this.queue = [];
    this.working = false;
    this.sentToday = 0;
    this.day = new Date().toDateString();
    this.stopping = false;
    this.retries = 0;
  }

  async start() {
    this.stopping = false;
    fs.mkdirSync(this.dir, { recursive: true });
    const { state, saveCreds } = await useMultiFileAuthState(this.dir);
    const { version } = await fetchLatestBaileysVersion().catch(() => ({ version: undefined }));
    this.state = 'connecting';
    const sock = makeWASocket({
      version,
      auth: state,
      logger: pino({ level: 'silent' }),
      browser: Browsers.appropriate('SoftSchool'),
      printQRInTerminal: false,
      markOnlineOnConnect: false,
      syncFullHistory: false,
    });
    this.sock = sock;
    sock.ev.on('creds.update', saveCreds);

    sock.ev.on('connection.update', async (u) => {
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
          this.state = 'disconnected'; this.me = null;
          fs.rmSync(this.dir, { recursive: true, force: true });
          return;
        }
        this.state = 'connecting';
        const wait = Math.min(30000, 2000 * ++this.retries);
        log.info({ school: this.schoolId, code, wait }, 'reconnecting');
        setTimeout(() => this.start().catch((e) => log.error(e)), wait);
      }
    });
  }

  view() {
    return {
      schoolId: this.schoolId,
      state: this.state,
      connected: this.state === 'connected',
      number: this.me,
      qr: this.qr,
      pairingCode: this.pairingCode,
      queued: this.queue.length,
      sentToday: this.sentToday,
      dailyLimit: cfg.dailyLimit,
    };
  }

  enqueue(items, country) {
    const out = [];
    for (const it of items) {
      const to = normalizePhone(it.to, country);
      if (!to) { out.push({ to: it.to, accepted: false, reason: 'invalid number' }); continue; }
      this.queue.push({ to, text: String(it.text || '').slice(0, 4000) });
      out.push({ to, accepted: true });
    }
    this.pump();
    return out;
  }

  async pump() {
    if (this.working || this.state !== 'connected') return;
    this.working = true;
    try {
      while (this.queue.length && this.state === 'connected') {
        if (this.day !== new Date().toDateString()) { this.day = new Date().toDateString(); this.sentToday = 0; }
        if (this.sentToday >= cfg.dailyLimit) { log.warn({ school: this.schoolId }, 'daily limit reached'); break; }
        const m = this.queue[0];
        try {
          const [res] = (await this.sock.onWhatsApp(m.to)) || [];
          if (res?.exists) {
            await this.sock.sendPresenceUpdate('composing', res.jid);
            await sleep(rand(800, 2000));
            await this.sock.sendMessage(res.jid, { text: m.text });
            this.sentToday++;
          } else {
            log.info({ to: m.to }, 'number is not on WhatsApp, skipped');
          }
          this.queue.shift();
        } catch (e) {
          log.warn({ e: e.message }, 'send failed');
          this.queue.shift(); // do not loop forever on a bad message
        }
        await sleep(rand(cfg.minDelay, cfg.maxDelay));
      }
    } finally { this.working = false; }
  }

  async logout() {
    this.stopping = true;
    this.queue = [];
    try { await this.sock?.logout(); } catch { /* already closed */ }
    try { this.sock?.end(undefined); } catch { /* noop */ }
    fs.rmSync(this.dir, { recursive: true, force: true });
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

app.get('/health', (_q, res) => res.json({ ok: true, sessions: sessions.size }));

// Start linking. body: { phone?: "923001234567" }  -> phone given = pairing-code mode, else QR mode
app.post('/sessions/:schoolId/connect', async (req, res) => {
  const s = getSession(req.params.schoolId, true);
  if (!s) return res.status(400).json({ error: 'bad schoolId' });
  if (s.state === 'connected') return res.json(s.view());
  s.pairPhone = req.body?.phone ? normalizePhone(req.body.phone, req.body.country) : null;
  s.qr = null; s.pairingCode = null;
  if (s.sock && ['qr', 'pairing', 'connecting'].includes(s.state)) {
    s.stopping = true; try { s.sock.end(undefined); } catch { /* noop */ }
  }
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

// body: { messages: [{ to, text }], country? }   (single send = one item)
app.post('/sessions/:schoolId/send', (req, res) => {
  const s = getSession(req.params.schoolId);
  if (!s || s.state !== 'connected') return res.status(409).json({ error: 'whatsapp not connected' });
  const items = Array.isArray(req.body?.messages) ? req.body.messages : [];
  if (!items.length || items.length > 1000) return res.status(400).json({ error: '1..1000 messages required' });
  res.json({ results: s.enqueue(items, req.body.country), queued: s.queue.length });
});

// Restore every previously linked school on boot (no QR needed again)
for (const dir of fs.readdirSync(cfg.sessionsDir)) {
  if (!fs.existsSync(path.join(cfg.sessionsDir, dir, 'creds.json'))) continue;
  getSession(dir, true).start().catch((e) => log.error(e, 'restore failed'));
}

app.listen(cfg.port, cfg.host, () => log.info(`WhatsApp service on ${cfg.host}:${cfg.port}`));
