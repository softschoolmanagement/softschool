package com.softschool.backend.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks failed school-login attempts per username and locks the account
 * out for a cooldown period once too many failures happen in a row.
 *
 * This is deliberately separate from {@link RateLimiter}: that class
 * throttles request *rate* (per-IP and per-account) so nothing can flood
 * the server, and resets on a rolling time window regardless of whether
 * any individual attempt succeeded. This class instead implements a
 * user-facing account-lockout policy with escalating warnings, and only
 * resets on a *successful* login:
 *
 *  - Attempts 1-2: plain "invalid username or password", no count shown.
 *  - Attempts 3-4: same, plus how many attempts have been made so far and
 *    how many remain before lockout (e.g. "You have made 3 login
 *    attempts. 2 attempt(s) left.").
 *  - Attempt 5: the account is locked for {@value #LOCKOUT_MINUTES}
 *    minutes. Every login attempt for that username - even with the
 *    correct password - is rejected until the lockout expires.
 *  - Any successful login immediately clears the failure count.
 *
 * In-memory, per-JVM-instance, matching RateLimiter's own scaling note: if
 * this backend is ever run as more than one instance behind a load
 * balancer, swap this for a shared store (e.g. Redis) so the lockout is
 * enforced across the whole fleet.
 */
@Component
public class LoginAttemptService {

    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_MINUTES = 7;

    private static final class Attempts {
        int failureCount = 0;
        Instant lockedUntil = null;
    }

    private final Map<String, Attempts> attemptsByUsername = new ConcurrentHashMap<>();

    /** Result of checking whether an account is currently locked out. */
    public static final class LockStatus {
        public final boolean locked;
        public final long retryAfterSeconds;

        LockStatus(boolean locked, long retryAfterSeconds) {
            this.locked = locked;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static final LockStatus NOT_LOCKED = new LockStatus(false, 0);
    }

    /** Result of recording one failed attempt. */
    public static final class FailureResult {
        public final int attemptCount;
        public final int attemptsLeft;
        public final boolean justLocked;
        public final long retryAfterSeconds;

        FailureResult(int attemptCount, int attemptsLeft, boolean justLocked, long retryAfterSeconds) {
            this.attemptCount = attemptCount;
            this.attemptsLeft = attemptsLeft;
            this.justLocked = justLocked;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /**
     * Call BEFORE checking credentials. If the account is currently
     * locked, the attempt must be rejected outright - even a correct
     * password doesn't get through during the cooldown.
     */
    public LockStatus checkLocked(String username) {
        Attempts attempts = attemptsByUsername.get(normalize(username));
        if (attempts == null || attempts.lockedUntil == null) {
            return LockStatus.NOT_LOCKED;
        }
        synchronized (attempts) {
            if (attempts.lockedUntil == null) return LockStatus.NOT_LOCKED;
            Instant now = Instant.now();
            if (now.isBefore(attempts.lockedUntil)) {
                long secondsLeft = Math.max(1, attempts.lockedUntil.getEpochSecond() - now.getEpochSecond());
                return new LockStatus(true, secondsLeft);
            }
            // Lockout has expired - clear it so the next failure starts a fresh count.
            attempts.lockedUntil = null;
            attempts.failureCount = 0;
            return LockStatus.NOT_LOCKED;
        }
    }

    /** Call after a failed (invalid credential) login attempt. */
    public FailureResult recordFailure(String username) {
        Attempts attempts = attemptsByUsername.computeIfAbsent(normalize(username), k -> new Attempts());
        synchronized (attempts) {
            attempts.failureCount++;
            if (attempts.failureCount >= MAX_ATTEMPTS) {
                long retryAfterSeconds = LOCKOUT_MINUTES * 60;
                attempts.lockedUntil = Instant.now().plusSeconds(retryAfterSeconds);
                return new FailureResult(attempts.failureCount, 0, true, retryAfterSeconds);
            }
            int left = MAX_ATTEMPTS - attempts.failureCount;
            return new FailureResult(attempts.failureCount, left, false, 0);
        }
    }

    /** Call after a successful login - clears any failure history for this account. */
    public void recordSuccess(String username) {
        attemptsByUsername.remove(normalize(username));
    }

    /** Periodic housekeeping so idle/one-off usernames don't leak memory forever. */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void cleanupExpired() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        attemptsByUsername.entrySet().removeIf(e -> {
            Attempts a = e.getValue();
            synchronized (a) {
                boolean neverFailed = a.failureCount == 0 && a.lockedUntil == null;
                boolean lockExpiredLongAgo = a.lockedUntil != null && a.lockedUntil.isBefore(cutoff);
                return neverFailed || lockExpiredLongAgo;
            }
        });
    }
}