package com.softschool.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Server-side cache for the student endpoints (Manage Students, Attendance,
 * Finance and the dashboard all read them, several on a 6-10 second poll).
 *
 * WHAT IS CACHED
 *  - The FINISHED JSON of GET /api/students (full), /list, /sync and
 *    /summary, one entry per (endpoint, school). It is stored already
 *    serialised AND already gzipped, so a cache hit costs no MySQL query,
 *    no Hibernate entity/DTO materialisation, no Jackson serialisation and
 *    no per-request gzip — just a byte[] copy to the socket.
 *  - The decoded bytes of a student's photo / B-Form (the /{regNo}/photo and
 *    /{regNo}/bform endpoints), so the browser's burst of per-student image
 *    requests no longer loads each student's whole row (LONGTEXT columns
 *    included) from MySQL one at a time.
 *
 * WHY IT IS NEVER STALE
 *  Every insert / update / delete of a Student row — from ANY controller or
 *  service, including saveAll() batches — fires StudentCacheInvalidationListener,
 *  which calls {@link #studentChanged}. That evicts the school's cached JSON
 *  and that student's cached files immediately AND again once the database
 *  transaction has finished (the second eviction closes the tiny window where
 *  another request could re-cache the old row just before the commit lands).
 *  A TTL (app.cache.students.ttl-seconds) is the final safety net.
 *
 * MEMORY
 *  Both caches are bounded by total BYTES (app.cache.students.json-max-mb /
 *  files-max-mb), not entry count, so a school with legacy base64 photos can
 *  never grow the heap without limit — least-recently/frequently-used
 *  entries are dropped first.
 *
 * CONCURRENCY
 *  Caffeine computes a missing entry once per key: if 14 dashboard calls ask
 *  for the same school's roster at the same instant, ONE hits MySQL and the
 *  rest wait for that result instead of stampeding the database.
 *
 * SINGLE-INSTANCE NOTE
 *  This is an in-process cache. If the backend is ever scaled to more than
 *  one instance, eviction on instance A cannot reach instance B, so lower
 *  app.cache.students.ttl-seconds (e.g. 15-30) or set
 *  STUDENT_CACHE_ENABLED=false.
 */
@Service
public class StudentCacheService {

    private static final Logger log = LoggerFactory.getLogger(StudentCacheService.class);

    /** Which student endpoint a cached JSON payload belongs to. */
    public enum Kind { FULL, LIST, SYNC, SUMMARY }

    /** Pre-serialised, pre-gzipped JSON body. */
    public static final class CachedJson {
        private final byte[] gzip;

        CachedJson(byte[] gzip) {
            this.gzip = gzip;
        }

        public byte[] gzipBytes() {
            return gzip;
        }

        public byte[] plainBytes() throws IOException {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
                return in.readAllBytes();
            }
        }
    }

    /** Decoded photo / B-Form bytes plus the media type detected from them. */
    public static final class CachedFile {
        private final byte[] bytes;
        private final String mediaType;

        public CachedFile(byte[] bytes, String mediaType) {
            this.bytes = bytes;
            this.mediaType = mediaType;
        }

        public byte[] bytes() {
            return bytes;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    private static final char SEP = '\u0000';
    /** regNo placeholder meaning "evict the whole school" in the after-commit set. */
    private static final String SCHOOL_WIDE = "\u0001*";

    /** Set once the bean exists so the JPA entity listener (not a Spring bean) can reach it. */
    private static volatile StudentCacheService instance;

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long ttlSeconds;
    private final long jsonMaxBytes;
    private final long filesMaxBytes;

    private Cache<String, CachedJson> jsonCache;
    private Cache<String, CachedFile> fileCache;

    public StudentCacheService(ObjectMapper objectMapper,
                               @Value("${app.cache.students.enabled:true}") boolean enabled,
                               @Value("${app.cache.students.ttl-seconds:300}") long ttlSeconds,
                               @Value("${app.cache.students.json-max-mb:48}") long jsonMaxMb,
                               @Value("${app.cache.students.files-max-mb:48}") long filesMaxMb) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttlSeconds = Math.max(5, ttlSeconds);
        this.jsonMaxBytes = Math.max(1, jsonMaxMb) * 1024L * 1024L;
        this.filesMaxBytes = Math.max(1, filesMaxMb) * 1024L * 1024L;
    }

    @PostConstruct
    void init() {
        jsonCache = Caffeine.newBuilder()
                .maximumWeight(jsonMaxBytes)
                .weigher((String k, CachedJson v) -> v.gzip.length)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        fileCache = Caffeine.newBuilder()
                .maximumWeight(filesMaxBytes)
                .weigher((String k, CachedFile v) -> v.bytes.length)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        instance = this;
        log.info("Student cache {} (ttl={}s, json<={}MB, files<={}MB)",
                enabled ? "ENABLED" : "DISABLED", ttlSeconds,
                jsonMaxBytes / (1024 * 1024), filesMaxBytes / (1024 * 1024));
    }

    @PreDestroy
    void shutdown() {
        if (instance == this) {
            instance = null;
        }
    }

    // ── JSON endpoints ──────────────────────────────────────────────────

    /**
     * Returns the cached JSON for this endpoint + school, running {@code loader}
     * (the database query) only when it is not cached yet. Exceptions from the
     * loader propagate and nothing is cached.
     */
    public CachedJson getJson(Kind kind, String schoolId, Supplier<?> loader) {
        if (!enabled) {
            return build(loader);
        }
        return jsonCache.get(jsonKey(kind, schoolId), k -> build(loader));
    }

    private CachedJson build(Supplier<?> loader) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(loader.get());
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, json.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(json);
            }
            return new CachedJson(bos.toByteArray());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds the HTTP response for a cached payload. Browsers (which always
     * send Accept-Encoding: gzip) get the stored gzip bytes as-is; Tomcat's
     * own compression stays out of the way because Content-Encoding is
     * already set. A client that does not accept gzip gets plain JSON.
     *
     * Cache-Control is deliberately NOT touched here: SecurityHeadersFilter
     * sends "no-store" on every /api/** response so authenticated student
     * data is never written to a browser or proxy cache. This cache lives
     * only in the server's memory.
     */
    public ResponseEntity<byte[]> toResponse(CachedJson json, String acceptEncoding) {
        boolean gzipOk = acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
        if (gzipOk) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .header(HttpHeaders.VARY, "Accept-Encoding")
                    .body(json.gzipBytes());
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.VARY, "Accept-Encoding")
                    .body(json.plainBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Photo / B-Form files ────────────────────────────────────────────

    /**
     * Returns the cached file bytes for one student's photo ("photo") or
     * B-Form ("bform"). {@code loader} reads/decodes the file and returns
     * null when there is nothing usable; null is never cached.
     */
    public CachedFile getFile(String schoolId, String type, String regNo, Supplier<CachedFile> loader) {
        if (!enabled) {
            return loader.get();
        }
        return fileCache.get(fileKey(schoolId, type, regNo), k -> loader.get());
    }

    // ── Invalidation ────────────────────────────────────────────────────

    /**
     * Called (via StudentCacheInvalidationListener) after any Student row is
     * inserted, updated or deleted. Safe to call from any thread, with or
     * without an active transaction.
     */
    public static void studentChanged(String schoolId, String regNo) {
        StudentCacheService svc = instance;
        if (svc == null || schoolId == null) {
            return;
        }
        svc.evictStudent(schoolId, regNo);
        svc.evictAgainAfterTransaction(schoolId, regNo);
    }

    /** Drops this school's cached JSON and one student's cached files. */
    public void evictStudent(String schoolId, String regNo) {
        if (schoolId == null) {
            return;
        }
        for (Kind kind : Kind.values()) {
            jsonCache.invalidate(jsonKey(kind, schoolId));
        }
        if (regNo != null) {
            fileCache.invalidate(fileKey(schoolId, "photo", regNo));
            fileCache.invalidate(fileKey(schoolId, "bform", regNo));
        }
    }

    /**
     * Drops EVERYTHING cached for a school (all JSON kinds and every cached
     * file). Use after a bulk change that can rename regNos, e.g. the super
     * admin's registration-prefix cascade.
     */
    public void evictSchool(String schoolId) {
        if (schoolId == null) {
            return;
        }
        for (Kind kind : Kind.values()) {
            jsonCache.invalidate(jsonKey(kind, schoolId));
        }
        String prefix = schoolId + SEP;
        fileCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        evictAgainAfterTransaction(schoolId, SCHOOL_WIDE);
    }

    /**
     * Evicts once more after the surrounding transaction has finished. The
     * entity listener fires at flush time, i.e. BEFORE the commit is visible
     * to other connections; a request arriving in that gap would re-cache the
     * old row. Evicting again after completion removes that entry.
     */
    private void evictAgainAfterTransaction(String schoolId, String regNo) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof EvictAfterCompletion) {
                ((EvictAfterCompletion) sync).add(schoolId, regNo);
                return;
            }
        }
        EvictAfterCompletion created = new EvictAfterCompletion(this);
        created.add(schoolId, regNo);
        TransactionSynchronizationManager.registerSynchronization(created);
    }

    /** One per transaction, so a saveAll() of 500 students costs one callback, not 500. */
    private static final class EvictAfterCompletion implements TransactionSynchronization {
        private final StudentCacheService service;
        private final Set<String> pending = new LinkedHashSet<>();

        EvictAfterCompletion(StudentCacheService service) {
            this.service = service;
        }

        void add(String schoolId, String regNo) {
            pending.add(schoolId + SEP + (regNo == null ? "" : regNo));
        }

        @Override
        public void afterCompletion(int status) {
            for (String entry : pending) {
                int cut = entry.indexOf(SEP);
                String schoolId = entry.substring(0, cut);
                String regNo = entry.substring(cut + 1);
                if (SCHOOL_WIDE.equals(regNo)) {
                    String prefix = schoolId + SEP;
                    for (Kind kind : Kind.values()) {
                        service.jsonCache.invalidate(jsonKey(kind, schoolId));
                    }
                    service.fileCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
                } else {
                    service.evictStudent(schoolId, regNo.isEmpty() ? null : regNo);
                }
            }
        }
    }

    private static String jsonKey(Kind kind, String schoolId) {
        return kind.name() + SEP + schoolId;
    }

    private static String fileKey(String schoolId, String type, String regNo) {
        return schoolId + SEP + type + SEP + regNo;
    }
}
