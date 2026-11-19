import assert from 'node:assert/strict';
import { SendGuard } from './safety.js';
const MIN = 60000, DAY = 86400000;
let t = Date.UTC(2026, 10, 18, 7, 0, 0);           // 12:00 Karachi (UTC+5), inside the window
const clock = { now: () => t, rand: (a) => a };
const mk = (o = {}, s = {}) => new SendGuard({ tz: 'Asia/Karachi', ...o }, s, clock);

// no time-of-day restriction any more: 22:00 and 03:00 Karachi are fine
{ const g = mk(); t = Date.UTC(2026, 10, 18, 17, 0, 0); assert.equal(g.check().ok, true);
  t = Date.UTC(2026, 10, 17, 22, 0, 0); assert.equal(g.check().ok, true);
  t = Date.UTC(2026, 10, 18, 7, 0, 0); assert.equal(g.check().ok, true); }

// no hourly limit: a burst of messages is only slowed by the batch rests, never by an hourly cap
{ const g = mk({ batchSize: 100000 }); t = Date.UTC(2026, 10, 18, 7, 0, 0);
  for (let i = 0; i < 120; i++) { assert.equal(g.check().ok, true); g.recordSent('9230000' + String(i).padStart(5, '0'), 'x' + i); t += 1000; } }

// no school limit set => no daily limit at all (not even for a freshly started guard)
{ const g = mk({ batchSize: 100000 }); t = Date.UTC(2026, 10, 18, 7, 0, 0);
  assert.equal(g.effectiveDaily(), null);
  for (let i = 0; i < 400; i++) g.recordSent('9231000' + String(i).padStart(5, '0'), 'y' + i);
  assert.equal(g.check().ok, true); assert.equal(g.snapshot().dailyLimit, null); }

// the school's own daily limit is enforced and resets next local day; no server ceiling above it
{ const g = mk({ batchSize: 1000 }); g.setUserLimit(3); t = Date.UTC(2026, 10, 18, 5, 0, 0);
  for (let i = 0; i < 3; i++) { g.recordSent('9231111111' + i, 'm' + i); t += 1000; }
  assert.equal(g.check().reason, 'daily_limit'); t += DAY; assert.equal(g.check().ok, true);
  g.setUserLimit(5000); assert.equal(g.effectiveDaily(), 5000); g.setUserLimit(0); assert.equal(g.effectiveDaily(), null); }

// batch cool-down pause
{ const g = mk({ batchSize: 10, batchPauseMin: 3 * MIN, batchPauseMax: 3 * MIN, }); t = Date.UTC(2026, 10, 18, 7, 0, 0);
  let n = 0; while (g.check().ok && n < 50) { g.recordSent('923222222' + String(n).padStart(3, '0'), 'a' + n); n++; t += 1000; }
  assert.ok(n >= 7 && n <= 13, 'batch size ~10, got ' + n); const c = g.check(); assert.equal(c.reason, 'cooldown'); assert.ok(c.waitMs <= 3 * MIN);
  t += 3 * MIN + 1; assert.equal(g.check().ok, true); }

// per-recipient cap and duplicate guard
{ const g = mk({ perRecipientDaily: 2 }); assert.equal(g.canSendTo('92300', 'hi').ok, true); g.recordSent('92300', 'hi');
  assert.equal(g.canSendTo('92300', 'hi').reason, 'duplicate'); assert.equal(g.canSendTo('92300', 'other').ok, true);
  g.recordSent('92300', 'other'); assert.equal(g.canSendTo('92300', 'third').reason, 'recipient_cap'); }

// default: 4 messages per parent number per day, then recipient_cap; counter resets next day
{ t = Date.UTC(2026, 10, 18, 7, 0, 0); const g = mk(); for (let i = 0; i < 4; i++) { assert.equal(g.canSendTo('92309', 'm' + i).ok, true); g.recordSent('92309', 'm' + i); }
  assert.equal(g.canSendTo('92309', 'm5').reason, 'recipient_cap'); t += DAY; assert.equal(g.canSendTo('92309', 'm5').ok, true); }

// no warm-up: a freshly created guard is not throttled
{ t = Date.UTC(2026, 10, 18, 7, 0, 0); const g = mk(); assert.equal(g.effectiveDaily(), null); assert.equal(g.snapshot().warmupDay, undefined); }

// failure circuit breaker
{ t = Date.UTC(2026, 10, 18, 7, 0, 0); const g = mk({ failLimit: 3, failPause: 15 * MIN }); for (let i = 0; i < 3; i++) g.recordFailure();
  assert.equal(g.check().reason, 'errors'); t += 15 * MIN + 1; assert.equal(g.check().ok, true); }

// state survives a JSON round trip (restart)
{ const g = mk(); g.recordSent('92301', 'z'); const g2 = mk({}, JSON.parse(JSON.stringify(g))); assert.equal(g2.canSendTo('92301', 'z').reason, 'duplicate'); assert.equal(g2.snapshot().sentToday, 1); }
console.log('ALL GUARD TESTS PASSED');
