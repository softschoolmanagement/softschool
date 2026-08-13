package com.softschool.backend.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal in-memory rate limiter used to protect every public REST endpoint
 * from abuse / accidental flooding (OWASP API4:2023 - Unrestricted Resource
 * Consumption; see also OWASP ASVS 2.2.1 on throttling authentication).
 *
 * ── Algorithm ────────────────────────────────────────────────────────────
 * Fixed window counter, per (bucket, key) pair:
 *   - "bucket" groups requests that share a limit/window, e.g. "general-ip"
 *     or "auth-identity". Different buckets never share counters, so a
 *     strict login limit can't be "used up" by unrelated traffic.
 *   - "key" identifies WHO is being limited within a bucket - typically a
 *     client IP address, or "endpointPath:accountIdentifier" for
 *     per-account throttling on sensitive endpoints.
 *
 * Fixed windows are simple and predictable, at the cost of allowing a short
 * burst right at a window boundary (e.g. up to ~2x capacity across the
 * boundary in the worst case). That trade-off is acceptable for this app's
 * traffic; if stricter smoothing is ever needed, swap this class's guts for
 * a sliding-window or token-bucket algorithm behind the same tryConsume()
 * contract below.
 *
 * ── Scaling note ─────────────────────────────────────────────────────────
 * State lives only in this JVM's memory - zero new dependencies, so this
 * works out of the box on a single instance. If this backend is ever run
 * as more than one instance behind a load balancer, limits become "per
 * instance" rather than global across the fleet. For that case, back this
 * class with a shared store (e.g. Redis) instead, keeping the same
 * tryConsume() method signature so RateLimitFilter doesn't need to change.
 */
@Component
public class RateLimiter {

    /** One fixed window's worth of state for a single (bucket, key). */
    private static final class Window {
        final AtomicLong windowStartMillis;
        final AtomicInteger count;

        Window(long now) {
            this.windowStartMillis = new AtomicLong(now);
            this.count = new AtomicInteger(0);
        }
    }

    // bucket name -> (key -> window). ConcurrentHashMap so requests from
    // different clients/buckets are never blocked by each other; only
    // requests hitting the *same* key briefly synchronize (see below).
    private final Map<String, Map<String, Window>> buckets = new ConcurrentHashMap<>();

    /** Result of a single tryConsume() call, used to build response headers. */
    public static final class Decision {
        public final boolean allowed;
        public final int limit;
        public final int remaining;
        public final long retryAfterSeconds;

        Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
            this.allowed = allowed;
            this.limit = limit;
            this.remaining = remaining;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /**
     * Attempts to consume one "slot" for {@code key} inside {@code bucket}.
     * Thread-safe; safe to call concurrently from many requests.
     *
     * @param bucket       logical group of limits, e.g. "general-ip"
     * @param key          who is being limited, e.g. the caller's IP
     * @param capacity     max requests allowed per window (must be > 0)
     * @param windowMillis window length in milliseconds (must be > 0)
     */
    public Decision tryConsume(String bucket, String key, int capacity, long windowMillis) {
        long now = System.currentTimeMillis();
        Map<String, Window> keys = buckets.computeIfAbsent(bucket, b -> new ConcurrentHashMap<>());
        Window window = keys.computeIfAbsent(key, k -> new Window(now));

        // Synchronized per-window (not globally) - contention only happens
        // if the SAME client fires truly concurrent requests, which is rare
        // and cheap to serialize for a counter increment.
        synchronized (window) {
            long elapsed = now - window.windowStartMillis.get();
            if (elapsed >= windowMillis) {
                // Window expired - start a fresh one.
                window.windowStartMillis.set(now);
                window.count.set(0);
                elapsed = 0;
            }

            long resetInSeconds = Math.max(1, (windowMillis - elapsed) / 1000);
            int used = window.count.get();

            if (used >= capacity) {
                return new Decision(false, capacity, 0, resetInSeconds);
            }

            int nowUsed = window.count.incrementAndGet();
            return new Decision(true, capacity, Math.max(0, capacity - nowUsed), resetInSeconds);
        }
    }

    /**
     * Periodic housekeeping so long-idle or one-off clients (e.g. a
     * dynamic-IP visitor who never comes back) don't leak memory forever.
     * Runs every 10 minutes; drops any window untouched for 30+ minutes.
     * Relies on @EnableScheduling, already present on BackendApplication.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void cleanupIdleWindows() {
        long now = System.currentTimeMillis();
        long maxIdleMillis = 30 * 60 * 1000L;
        for (Map<String, Window> keys : buckets.values()) {
            keys.entrySet().removeIf(e -> (now - e.getValue().windowStartMillis.get()) > maxIdleMillis);
        }
    }
}
