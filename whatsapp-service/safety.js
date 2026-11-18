// Anti-ban rules for ONE school's WhatsApp number. Pure logic (no WhatsApp, no I/O) so it can be tested
// and so the pacing rules live in one place. server.js asks this guard "may I send now?" before every message.
import crypto from 'node:crypto';

const MIN = 60_000;
const HOUR = 3_600_000;
const DAY = 86_400_000;

export const defaultOpts = {
  dailyLimit: 250,            // hard ceiling per number per day (a school can lower it, never raise it)
  hourlyLimit: 40,            // never more than this many in any rolling hour
  batchSize: 15,              // after ~this many messages in a row ...
  batchPauseMin: 3 * MIN,     // ... rest for 3-7 minutes, like a person would
  batchPauseMax: 7 * MIN,
  perRecipientDaily: 3,       // one parent number gets at most this many messages a day (siblings share numbers)
  windowStart: 8,             // only send between 08:00 and 21:00 (school local time); 0 and 24 = always
  windowEnd: 21,
  tz: 'Asia/Karachi',
  warmupDays: 7,              // a freshly linked number ramps up slowly over its first week
  warmupStart: 30,            // messages allowed on day one
  failLimit: 5,               // this many send failures in a row ...
  failPause: 15 * MIN,        // ... pauses the number for 15 minutes
};

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
      linkedAt: null,         // set only for a number linked while this service runs (=> warm-up applies)
      userDailyLimit: null,   // the school's own limit from Settings
      day: null, sentToday: 0,
      sentTimes: [],          // timestamps of the last hour
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
  hourOf(ms = this.now()) {
    const h = new Intl.DateTimeFormat('en-GB', { timeZone: this.o.tz, hour: 'numeric', hourCycle: 'h23' }).format(ms);
    return Number(h) % 24;
  }
  inWindow(ms = this.now()) {
    const { windowStart: a, windowEnd: b } = this.o;
    if (a <= 0 && b >= 24) return true;
    const h = this.hourOf(ms);
    return a < b ? (h >= a && h < b) : (h >= a || h < b);
  }

  rollDay() {
    const k = this.dayKey();
    if (this.s.day !== k) { this.s.day = k; this.s.sentToday = 0; this.s.perRecipient = {}; }
    const t = this.now();
    this.s.sentTimes = this.s.sentTimes.filter((x) => t - x < HOUR);
    for (const h of Object.keys(this.s.recent)) if (t - this.s.recent[h] > DAY) delete this.s.recent[h];
  }

  // ───────── limits ─────────
  /** Today's real cap: min(server ceiling, the school's own limit, warm-up allowance). */
  effectiveDaily() {
    let lim = this.o.dailyLimit;
    if (this.s.userDailyLimit) lim = Math.min(lim, Math.max(1, this.s.userDailyLimit));
    const w = this.warmupDay();
    if (w !== null) {
      const step = Math.ceil(Math.max(0, this.o.dailyLimit - this.o.warmupStart) / Math.max(1, this.o.warmupDays));
      lim = Math.min(lim, this.o.warmupStart + w * step);
    }
    return lim;
  }
  effectiveHourly() {
    return Math.min(this.o.hourlyLimit, Math.max(5, Math.ceil(this.effectiveDaily() / 4)));
  }
  /** 0-based day of the warm-up period, or null when the number is no longer (or never was) warming up. */
  warmupDay() {
    if (!this.s.linkedAt) return null;
    const d = Math.floor((this.now() - this.s.linkedAt) / DAY);
    return d < this.o.warmupDays ? d : null;
  }
  setUserLimit(n) {
    const v = Math.floor(Number(n));
    this.s.userDailyLimit = Number.isFinite(v) && v > 0 ? Math.min(v, this.o.dailyLimit) : null;
  }
  markLinked() { if (!this.s.linkedAt) this.s.linkedAt = this.now(); }

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
    if (!this.inWindow(t)) return { ok: false, waitMs: 5 * MIN, reason: 'quiet_hours' };
    if (this.s.sentToday >= this.effectiveDaily()) return { ok: false, waitMs: 10 * MIN, reason: 'daily_limit' };
    if (this.s.sentTimes.length >= this.effectiveHourly()) {
      const wait = this.s.sentTimes[0] + HOUR - t + this.rand(5_000, 60_000);
      return { ok: false, waitMs: Math.max(wait, 5_000), reason: 'hourly_limit' };
    }
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
    this.s.sentTimes.push(t);
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

  /** Estimated minutes until `queued` messages are out (pacing + rests; ignores quiet hours). */
  etaMinutes(queued, minDelay, maxDelay) {
    if (!queued) return 0;
    const per = (minDelay + maxDelay) / 2 + 3000;
    const rests = Math.floor(queued / Math.max(1, this.o.batchSize)) * ((this.o.batchPauseMin + this.o.batchPauseMax) / 2);
    const hourly = this.effectiveHourly();
    const hourWaits = Math.floor(queued / hourly) * HOUR * 0.5;
    return Math.ceil((queued * per + rests + hourWaits) / MIN);
  }

  snapshot() {
    this.rollDay();
    const t = this.now();
    return {
      sentToday: this.s.sentToday,
      sentThisHour: this.s.sentTimes.length,
      dailyLimit: this.effectiveDaily(),
      userDailyLimit: this.s.userDailyLimit,
      maxDailyLimit: this.o.dailyLimit,
      hourlyLimit: this.effectiveHourly(),
      warmupDay: this.warmupDay(),
      quietHours: !this.inWindow(t),
      window: `${this.o.windowStart}:00-${this.o.windowEnd}:00`,
      pausedReason: this.s.pauseUntil > t ? this.s.pauseReason : null,
      pausedForMs: this.s.pauseUntil > t ? this.s.pauseUntil - t : 0,
    };
  }

  toJSON() { return this.s; }
}

function hash(to, text) {
  return crypto.createHash('sha1').update(to + '\n' + text).digest('hex').slice(0, 16);
}
