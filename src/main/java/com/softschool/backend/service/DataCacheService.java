package com.softschool.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * General server-side read cache for the parent portal and the heavier admin
 * reads (attendance, finance). It complements {@link StudentCacheService},
 * which keeps handling the student endpoints and photos.
 *
 * HOW A CACHED VALUE STAYS FRESH
 *  Every cached value declares which kinds of data it depends on ({@link Dep}).
 *  The cache key contains the CURRENT version number of each of those
 *  dependencies for that school. When any row of that kind is inserted,
 *  updated or deleted (see {@link DataCacheInvalidationListener}), the school's
 *  version for that kind goes up, so the next read builds a different key and
 *  recomputes from MySQL. Nothing has to be searched or removed, so a write
 *  costs one counter increment no matter how many entries exist. Old entries
 *  are never read again and simply age out (TTL / size bound).
 *
 *  The version is bumped when the row is flushed AND again after the
 *  transaction finishes, which closes the small window where a concurrent
 *  reader could cache the old row under the new version just before the
 *  commit lands (same idea as StudentCacheService).
 *
 *  The TTL is only the safety net for the cases that bypass JPA entity events
 *  (bulk JPQL UPDATE/DELETE statements, or manual changes in MySQL).
 *
 * TWO FLAVOURS
 *  - {@link #get}: stores the Java object (small results: parent portal views).
 *    Bounded by an approximate weight (rows / entries), not by byte size.
 *  - {@link #getJson}: stores the finished, gzipped JSON (large admin lists
 *    that can carry big text columns). Bounded by BYTES, like the student cache.
 *
 * RULES FOR CALLERS
 *  - A loader MUST NOT call this cache again (Caffeine forbids updating other
 *    mappings from inside a loader). Do the nested reads before or after.
 *  - Cached objects are shared between requests: treat them as read-only.
 *  - Never cache something that depends on the caller's identity unless the
 *    identity (e.g. the parent's phone) is part of the key.
 *
 * SINGLE-INSTANCE NOTE
 *  This is an in-process cache. With more than one backend instance, a write on
 *  instance A cannot reach instance B, so lower app.cache.data.ttl-seconds (for
 *  example 10-15) or set DATA_CACHE_ENABLED=false.
 */
@Service
public class DataCacheService {

    private static final Logger log = LoggerFactory.getLogger(DataCacheService.class);

    /** The kinds of rows a cached value can depend on. One entity maps to one kind. */
    public enum Dep {
        STUDENTS, STAFF, ATTENDANCE, FINANCE, DIARY, ANNOUNCEMENTS, TESTS,
        TIMETABLE, RESULTS, MESSAGES, READMARKS, SETTINGS, ACCOUNTS, LINKS
    }

    private static final char SEP = '\u0000';
    private static final long MAX_TTL_SECONDS = 3600;

    /** Set once the bean exists so the JPA entity listener (not a Spring bean) can reach it. */
    private static volatile DataCacheService instance;

    private static final class Slot {
        final Object value;
        final int weight;
        final long ttlNanos;

        Slot(Object value, int weight, long ttlNanos) {
            this.value = value;
            this.weight = weight;
            this.ttlNanos = ttlNanos;
        }
    }

    /** Every entry carries its own time-to-live. */
    private static final class SlotExpiry implements Expiry<String, Slot> {
        @Override
        public long expireAfterCreate(String key, Slot value, long currentTime) {
            return value.ttlNanos;
        }

        @Override
        public long expireAfterUpdate(String key, Slot value, long currentTime, long currentDuration) {
            return value.ttlNanos;
        }

        @Override
        public long expireAfterRead(String key, Slot value, long currentTime, long currentDuration) {
            return currentDuration;
        }
    }

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long defaultTtlSeconds;
    private final long maxWeight;
    private final long jsonMaxBytes;

    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
    private Cache<String, Slot> objects;
    private Cache<String, StudentCacheService.CachedJson> jsons;

    public DataCacheService(ObjectMapper objectMapper,
                            @Value("${app.cache.data.enabled:true}") boolean enabled,
                            @Value("${app.cache.data.ttl-seconds:60}") long ttlSeconds,
                            @Value("${app.cache.data.max-weight:50000}") long maxWeight,
                            @Value("${app.cache.data.json-max-mb:48}") long jsonMaxMb) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.defaultTtlSeconds = Math.max(1, Math.min(ttlSeconds, MAX_TTL_SECONDS));
        this.maxWeight = Math.max(100, maxWeight);
        this.jsonMaxBytes = Math.max(1, jsonMaxMb) * 1024L * 1024L;
    }

    @PostConstruct
    void init() {
        objects = Caffeine.newBuilder()
                .maximumWeight(maxWeight)
                .weigher((String k, Slot v) -> v.weight)
                .expireAfter(new SlotExpiry())
                .build();
        jsons = Caffeine.newBuilder()
                .maximumWeight(jsonMaxBytes)
                .weigher((String k, StudentCacheService.CachedJson v) -> v.gzipBytes().length)
                .expireAfterWrite(Duration.ofSeconds(defaultTtlSeconds))
                .build();
        instance = this;
        log.info("Data cache {} (default ttl={}s, objects<={} weight units, json<={}MB)",
                enabled ? "ENABLED" : "DISABLED", defaultTtlSeconds, maxWeight, jsonMaxBytes / (1024 * 1024));
    }

    @PreDestroy
    void shutdown() {
        if (instance == this) {
            instance = null;
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────

    /**
     * Returns the cached object for (school, subKey, versions of deps), running
     * {@code loader} only when it is not cached. Exceptions from the loader
     * propagate and nothing is cached. A null result is cached too (until a
     * dependency changes or the TTL passes).
     *
     * @param ttlSeconds how long the entry may live; 0 or less uses the default
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String schoolId, String subKey, long ttlSeconds, Supplier<T> loader, Dep... deps) {
        if (!enabled || schoolId == null) {
            return loader.get();
        }
        final long ttl = ttlSeconds <= 0 ? defaultTtlSeconds : Math.min(ttlSeconds, MAX_TTL_SECONDS);
        Slot slot = objects.get(key(schoolId, subKey, deps), k -> {
            T value = loader.get();
            return new Slot(value, weightOf(value), TimeUnit.SECONDS.toNanos(ttl));
        });
        return slot == null ? null : (T) slot.value;
    }

    /**
     * Returns the cached, already serialised and gzipped JSON of whatever
     * {@code loader} returns. Use {@link StudentCacheService#toResponse} to turn
     * it into the HTTP response.
     */
    public StudentCacheService.CachedJson getJson(String schoolId, String subKey, Supplier<?> loader, Dep... deps) {
        if (!enabled || schoolId == null) {
            return buildJson(loader);
        }
        return jsons.get(key(schoolId, subKey, deps), k -> buildJson(loader));
    }

    private StudentCacheService.CachedJson buildJson(Supplier<?> loader) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(loader.get());
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, json.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(json);
            }
            return new StudentCacheService.CachedJson(bos.toByteArray());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int weightOf(Object value) {
        if (value instanceof Collection) {
            return Math.max(1, ((Collection<?>) value).size());
        }
        if (value instanceof Map) {
            return Math.max(1, ((Map<?, ?>) value).size());
        }
        return 1;
    }

    // ── Invalidation ────────────────────────────────────────────────────

    /**
     * Called (via the entity listeners) after a row of this kind was inserted,
     * updated or deleted in this school. Safe from any thread, with or without
     * an active transaction.
     */
    public static void changed(Dep dep, String schoolId) {
        DataCacheService svc = instance;
        if (svc == null || dep == null || schoolId == null) {
            return;
        }
        svc.bump(dep, schoolId);
        svc.bumpAgainAfterTransaction(dep, schoolId);
    }

    private void bump(Dep dep, String schoolId) {
        versions.computeIfAbsent(versionKey(dep, schoolId), k -> new AtomicLong()).incrementAndGet();
    }

    private long versionOf(Dep dep, String schoolId) {
        AtomicLong v = versions.get(versionKey(dep, schoolId));
        return v == null ? 0L : v.get();
    }

    private static String versionKey(Dep dep, String schoolId) {
        return dep.name() + SEP + schoolId;
    }

    private String key(String schoolId, String subKey, Dep[] deps) {
        StringBuilder sb = new StringBuilder(64).append(schoolId).append(SEP).append(subKey).append(SEP);
        if (deps != null) {
            for (Dep dep : deps) {
                sb.append(versionOf(dep, schoolId)).append('.');
            }
        }
        return sb.toString();
    }

    /**
     * The listener fires at flush time, i.e. BEFORE the commit is visible to
     * other connections. Bump once more when the transaction has finished so a
     * value cached in that gap is never read again.
     */
    private void bumpAgainAfterTransaction(Dep dep, String schoolId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof BumpAfterCompletion) {
                ((BumpAfterCompletion) sync).add(dep, schoolId);
                return;
            }
        }
        BumpAfterCompletion created = new BumpAfterCompletion(this);
        created.add(dep, schoolId);
        TransactionSynchronizationManager.registerSynchronization(created);
    }

    /** One per transaction, so saving 500 rows costs one callback, not 500. */
    private static final class BumpAfterCompletion implements TransactionSynchronization {
        private final DataCacheService service;
        private final Set<String> pending = new LinkedHashSet<>();

        BumpAfterCompletion(DataCacheService service) {
            this.service = service;
        }

        void add(Dep dep, String schoolId) {
            pending.add(dep.name() + SEP + schoolId);
        }

        @Override
        public void afterCompletion(int status) {
            for (String entry : pending) {
                int cut = entry.indexOf(SEP);
                Dep dep = Dep.valueOf(entry.substring(0, cut));
                service.bump(dep, entry.substring(cut + 1));
            }
        }
    }
}
