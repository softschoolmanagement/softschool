// Anti-ban rules for ONE school's WhatsApp number. Pure logic (no WhatsApp, no I/O) so it can be tested
// and so the pacing rules live in one place. server.js asks this guard "may I send now?" before every message.
import crypto from 'node:crypto';

const MIN = 60_000;
const DAY = 86_400_000;

export const defaultOpts = {
  batchSize: 15,              // after ~this many messages in a row ...
  batchPauseMin: 3 * MIN,     // ... rest for 3-7 minutes, like a person would
  batchPauseMax: 7 * MIN,
  perRecipientDaily: 4,       // one parent number gets at most this many messages a day (siblings share numbers)
  tz: 'Asia/Karachi',         // only used to decide when the "day" rolls over for the daily counters
  failLimit: 5,               // this many send failures in a row ...
  failPause: 15 * MIN,        // ... pauses the number for 15 minutes
};
// There is no server-side daily/hourly ceiling and no warm-up for new numbers any more.
// The only caps are: the school's own daily limit (Settings), and perRecipientDaily per parent number.

export class SendGuard {
  /**
   * @param opts   see defaultOpts
   * @param state  previously saved state (toJSON()) or undefined
   * @param clock  { now(): ms, rand(a,b): int } — injectable so tests are deterministic
   */
  constructor(opts = {}, state = {}, clock = {}) {
    this.o = { ...defaultOpts, ...opts };
    this.now = clock.now || (() => Date.now());
    this.rand = clock.rand || ((a, b) => a + Math.floor(Math.random() * (b - a + 1)));
    this.s = {
      userDailyLimit: null,   // the school's own limit from Settings (null = no daily limit)
      day: null, sentToday: 0,
      perRecipient: {},       // number -> messages today
      recent: {},             // hash(number+text) -> timestamp (24h duplicate guard)
      batchSent: 0, batchTarget: 0,
      pauseUntil: 0, pauseReason: null,
      fails: 0,
      ...state,
    };
    if (!this.s.batchTarget) this.newBatchTarget();
  }

  // ───────── time helpers (school-local) ─────────
  dayKey(ms = this.now()) {
    return new Intl.DateTimeFormat('en-CA', { timeZone: this.o.tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(ms);
  }

  rollDay() {
    const k = this.dayKey();
    if (this.s.day !== k) { this.s.day = k; this.s.sentToday = 0; this.s.perRecipient = {}; }
    const t = this.now();
    for (const h of Object.keys(this.s.recent)) if (t - this.s.recent[h] > DAY) delete this.s.recent[h];
  }

  // ───────── limits ─────────
  /** Today's cap: the school's own limit from Settings, or null when it has not set one (= unlimited). */
  effectiveDaily() { return this.s.userDailyLimit || null; }
  setUserLimit(n) {
    const v = Math.floor(Number(n));
    this.s.userDailyLimit = Number.isFinite(v) && v > 0 ? v : null;
  }

  newBatchTarget() {
    const b = this.o.batchSize;
    this.s.batchTarget = this.rand(Math.max(3, Math.round(b * 0.7)), Math.round(b * 1.3));
  }

  // ───────── decisions ─────────
  /** May the next message go out right now? Otherwise says how long to wait and why. */
  check() {
    this.rollDay();
    const t = this.now();
    if (this.s.pauseUntil > t) return { ok: false, waitMs: this.s.pauseUntil - t, reason: this.s.pauseReason || 'cooldown' };
    const daily = this.effectiveDaily();
    if (daily && this.s.sentToday >= daily) return { ok: false, waitMs: 10 * MIN, reason: 'daily_limit' };
    return { ok: true };
  }

  /** Per-message rules: duplicate text to the same number, or too many to one number today. */
  canSendTo(to, text) {
    this.rollDay();
    if ((this.s.perRecipient[to] || 0) >= this.o.perRecipientDaily) return { ok: false, reason: 'recipient_cap' };
    if (this.s.recent[hash(to, text)]) return { ok: false, reason: 'duplicate' };
    return { ok: true };
  }

  recordSent(to, text) {
    this.rollDay();
    const t = this.now();
    this.s.sentToday++;
    this.s.perRecipient[to] = (this.s.perRecipient[to] || 0) + 1;
    this.s.recent[hash(to, text)] = t;
    this.s.fails = 0;
    if (++this.s.batchSent >= this.s.batchTarget) {
      this.s.batchSent = 0;
      this.newBatchTarget();
      this.s.pauseUntil = t + this.rand(this.o.batchPauseMin, this.o.batchPauseMax);
      this.s.pauseReason = 'cooldown';
    }
  }

  recordFailure() {
    if (++this.s.fails >= this.o.failLimit) {
      this.s.fails = 0;
      this.s.pauseUntil = this.now() + this.o.failPause;
      this.s.pauseReason = 'errors';
    }
  }

  /** Estimated minutes until `queued` messages are out (pacing + rests). */
  etaMinutes(queued, minDelay, maxDelay) {
    if (!queued) return 0;
    const per = (minDelay + maxDelay) / 2 + 3000;
    const rests = Math.floor(queued / Math.max(1, this.o.batchSize)) * ((this.o.batchPauseMin + this.o.batchPauseMax) / 2);
    return Math.ceil((queued * per + rests) / MIN);
  }

  snapshot() {
    this.rollDay();
    const t = this.now();
    return {
      sentToday: this.s.sentToday,
      dailyLimit: this.effectiveDaily(),   // null = no daily limit
      perRecipientDaily: this.o.perRecipientDaily,
      pausedReason: this.s.pauseUntil > t ? this.s.pauseReason : null,
      pausedForMs: this.s.pauseUntil > t ? this.s.pauseUntil - t : 0,
    };
  }

  toJSON() { return this.s; }
}

function hash(to, text) {
  return crypto.createHash('sha1').update(to + '\n' + text).digest('hex').slice(0, 16);
}
