import assert from 'node:assert/strict';
import { SendGuard } from './safety.js';
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
let t = Date.UTC(2026, 10, 18, 7, 0, 0);           // 12:00 Karachi (UTC+5), inside the window
const clock = { now: () => t, rand: (a) => a };
const mk = (o = {}, s = {}) => new SendGuard({ tz: 'Asia/Karachi', ...o }, s, clock);

// no time-of-day restriction any more: 22:00 and 03:00 Karachi are fine
{ const g = mk(); t = Date.UTC(2026, 10, 18, 17, 0, 0); assert.equal(g.check().ok, true);
  t = Date.UTC(2026, 10, 17, 22, 0, 0); assert.equal(g.check().ok, true);
  t = Date.UTC(2026, 10, 18, 7, 0, 0); assert.equal(g.check().ok, true); }

// hourly limit + wait until oldest ages out
{ const g = mk({ hourlyLimit: 5, batchSize: 1000, dailyLimit: 1000 }); t = Date.UTC(2026, 10, 18, 7, 0, 0);
  for (let i = 0; i < 5; i++) { assert.equal(g.check().ok, true); g.recordSent('9230000000' + i, 'x' + i); t += 10_000; }
  const c = g.check(); assert.equal(c.reason, 'hourly_limit'); assert.ok(c.waitMs > 40 * MIN && c.waitMs < HOUR);
  t += HOUR; assert.equal(g.check().ok, true); }

// daily limit, then resets next local day
{ const g = mk({ dailyLimit: 3, hourlyLimit: 100, batchSize: 1000 }); t = Date.UTC(2026, 10, 18, 5, 0, 0);
  for (let i = 0; i < 3; i++) { g.recordSent('9231111111' + i, 'm' + i); t += 1000; }
  assert.equal(g.check().reason, 'daily_limit'); t += DAY; assert.equal(g.check().ok, true); }

// school's own limit can lower but never raise the ceiling
{ const g = mk({ dailyLimit: 100 }); g.setUserLimit(20); assert.equal(g.effectiveDaily(), 20);
  g.setUserLimit(5000); assert.equal(g.effectiveDaily(), 100); g.setUserLimit(null); assert.equal(g.effectiveDaily(), 100); }

// batch cool-down pause
{ const g = mk({ batchSize: 10, batchPauseMin: 3 * MIN, batchPauseMax: 3 * MIN, hourlyLimit: 1000, dailyLimit: 1000 }); t = Date.UTC(2026, 10, 18, 7, 0, 0);
  let n = 0; while (g.check().ok && n < 50) { g.recordSent('923222222' + String(n).padStart(3, '0'), 'a' + n); n++; t += 1000; }
  assert.ok(n >= 7 && n <= 13, 'batch size ~10, got ' + n); const c = g.check(); assert.equal(c.reason, 'cooldown'); assert.ok(c.waitMs <= 3 * MIN);
  t += 3 * MIN + 1; assert.equal(g.check().ok, true); }

// per-recipient cap and duplicate guard
{ const g = mk({ perRecipientDaily: 2 }); assert.equal(g.canSendTo('92300', 'hi').ok, true); g.recordSent('92300', 'hi');
  assert.equal(g.canSendTo('92300', 'hi').reason, 'duplicate'); assert.equal(g.canSendTo('92300', 'other').ok, true);
  g.recordSent('92300', 'other'); assert.equal(g.canSendTo('92300', 'third').reason, 'recipient_cap'); }

// warm-up for a freshly linked number; existing numbers (linkedAt null) are not throttled
{ t = Date.UTC(2026, 10, 18, 7, 0, 0); const g = mk({ dailyLimit: 250 }); g.markLinked();
  assert.equal(g.effectiveDaily(), 30); t += 3 * DAY; assert.ok(g.effectiveDaily() > 100 && g.effectiveDaily() < 250);
  t += 5 * DAY; assert.equal(g.effectiveDaily(), 250); const old = mk({ dailyLimit: 250 }); assert.equal(old.effectiveDaily(), 250); }

// failure circuit breaker
{ t = Date.UTC(2026, 10, 18, 7, 0, 0); const g = mk({ failLimit: 3, failPause: 15 * MIN }); for (let i = 0; i < 3; i++) g.recordFailure();
  assert.equal(g.check().reason, 'errors'); t += 15 * MIN + 1; assert.equal(g.check().ok, true); }

// state survives a JSON round trip (restart)
{ const g = mk(); g.recordSent('92301', 'z'); const g2 = mk({}, JSON.parse(JSON.stringify(g))); assert.equal(g2.canSendTo('92301', 'z').reason, 'duplicate'); assert.equal(g2.snapshot().sentToday, 1); }
console.log('ALL GUARD TESTS PASSED');
