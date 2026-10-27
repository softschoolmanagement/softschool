package com.softschool.backend.service;

import com.softschool.backend.model.Student;
import com.softschool.backend.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ONE-TIME migration: moves student photos / B-Forms that are stored as
 * base64 text inside students.photo / students.certData out of MySQL and
 * into real files on disk (uploads/photos, uploads/bforms), then points the
 * column at the file's relative path.
 *
 * It does NOTHING unless explicitly switched on:
 *
 *   MEDIA_MIGRATION_MODE=dry-run   decode + check every row, write a CSV
 *                                  report, change NOTHING.
 *   MEDIA_MIGRATION_MODE=run       do the migration.
 *   MEDIA_MIGRATION_SCHOOL_ID=...  optional: only this school.
 *   MEDIA_MIGRATION_LIMIT=50       optional: stop after this many rows.
 *
 * SAFETY RULES (this is what "no data loss" means here):
 *  1. The base64 is decoded with the SAME strict decoder the photo endpoint
 *     uses today, so what gets migrated is exactly what is displayed now.
 *  2. The file type is decided from the decoded bytes, never from the
 *     "data:...;" header.
 *  3. The file is written, then READ BACK and its SHA-256 compared with the
 *     decoded bytes. Only a verified file is ever referenced.
 *  4. The column is switched with a compare-and-set update guarded by the
 *     original length, so an edit made while the migration runs is never
 *     overwritten (0 rows match -> the new file is deleted, row untouched).
 *  5. Anything undecodable / unrecognised / failing is SKIPPED and left
 *     exactly as it was; the reason goes to the CSV log.
 *  6. Re-runnable: rows that already hold a "photos/..." or "bforms/..." path
 *     are skipped, so a stopped run can simply be started again.
 *
 * Always take a database backup first. The original base64 is overwritten by
 * the path on success, so the backup is the rollback.
 */
@Component
public class MediaMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaMigrationRunner.class);

    @Value("${app.media-migration.mode:off}")
    private String mode;

    @Value("${app.media-migration.school-id:}")
    private String schoolIdFilter;

    @Value("${app.media-migration.limit:0}")
    private int limit;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private StudentCacheService studentCache;

    @Override
    public void run(ApplicationArguments args) {
        String m = mode == null ? "off" : mode.trim().toLowerCase();
        if (!m.equals("dry-run") && !m.equals("run")) {
            return; // default: completely inert
        }
        final boolean apply = m.equals("run");
        Thread worker = new Thread(() -> {
            try {
                migrate(apply);
            } catch (Exception e) {
                log.error("[media-migration] aborted: {}", e.toString(), e);
            }
        }, "media-migration");
        worker.start();
    }

    private void migrate(boolean apply) throws IOException {
        String school = schoolIdFilter == null ? "" : schoolIdFilter.trim();
        String pattern = school.isEmpty() ? "%" : school;

        Path logDir = fileStorageService.getRoot().resolve("migration-logs");
        Files.createDirectories(logDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path logFile = logDir.resolve("media-migration-" + stamp + (apply ? "-run" : "-dryrun") + ".csv");

        log.info("[media-migration] START mode={} school={} limit={} log={}",
                apply ? "run" : "dry-run", school.isEmpty() ? "ALL" : school, limit, logFile);

        Map<String, Integer> stats = new LinkedHashMap<>();
        Set<String> touchedSchools = new HashSet<>();

        try (BufferedWriter out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            out.write("schoolId,regNo,field,status,oldChars,bytes,sha256,newPath,note\n");
            int[] processed = {0};

            processKind(true, pattern, school, apply, out, stats, touchedSchools, processed);
            processKind(false, pattern, school, apply, out, stats, touchedSchools, processed);
        }

        if (apply) {
            for (String sid : touchedSchools) {
                studentCache.evictSchool(sid); // JPQL bulk updates bypass the entity listener
            }
        }
        log.info("[media-migration] DONE {} -> details in {}", stats, logFile);
        if (apply) {
            log.info("[media-migration] Set MEDIA_MIGRATION_MODE back to 'off' and restart when you are finished.");
        }
    }

    private void processKind(boolean photo, String pattern, String school, boolean apply,
                             BufferedWriter out, Map<String, Integer> stats,
                             Set<String> touchedSchools, int[] processed) throws IOException {
        String field = photo ? "photo" : "certData";
        List<Long> ids = photo
                ? studentRepository.findLegacyPhotoIds(pattern)
                : studentRepository.findLegacyCertIds(pattern);
        log.info("[media-migration] {} rows with legacy {} to look at", ids.size(), field);

        for (Long id : ids) {
            if (limit > 0 && processed[0] >= limit) {
                log.info("[media-migration] limit of {} reached", limit);
                return;
            }
            Student s = studentRepository.findById(id).orElse(null);
            if (s == null) continue;
            if (!school.isEmpty() && !school.equals(s.getSchoolId())) continue; // '_' is a LIKE wildcard

            String raw = photo ? s.getPhoto() : s.getCertData();
            if (raw == null || raw.isBlank() || fileStorageService.isManagedPath(raw)) continue;

            processed[0]++;
            int oldChars = raw.length();
            String schoolId = s.getSchoolId();
            String regNo = s.getRegNo();

            byte[] bytes = decode(raw);
            if (bytes == null || bytes.length == 0) {
                record(out, stats, schoolId, regNo, field, "SKIP_NOT_BASE64", oldChars, 0, "", "",
                        "value is not decodable base64; left untouched");
                continue;
            }
            String ext = FileStorageService.detectExtension(bytes, Math.min(bytes.length, 16));
            if (ext == null || (photo && ".pdf".equals(ext))) {
                record(out, stats, schoolId, regNo, field, "SKIP_UNKNOWN_TYPE", oldChars, bytes.length, "", "",
                        "bytes are not a recognised image/PDF; left untouched");
                continue;
            }
            String sha = sha256(bytes);

            if (!apply) {
                record(out, stats, schoolId, regNo, field, "DRYRUN_OK", oldChars, bytes.length, sha, "",
                        "would migrate as " + ext);
                continue;
            }

            String rel = null;
            try {
                rel = fileStorageService.storeBytes(bytes, photo ? "photos" : "bforms", ext);
                byte[] back = Files.readAllBytes(fileStorageService.resolve(rel));
                if (!sha.equals(sha256(back))) {
                    throw new IOException("read-back hash mismatch");
                }
                int updated = photo
                        ? studentRepository.replaceLegacyPhoto(id, rel, oldChars)
                        : studentRepository.replaceLegacyCert(id, rel, oldChars);
                if (updated != 1) {
                    fileStorageService.deleteQuietly(rel);
                    record(out, stats, schoolId, regNo, field, "SKIP_CHANGED_DURING_MIGRATION", oldChars,
                            bytes.length, sha, "", "row was edited meanwhile; left untouched, run again");
                    continue;
                }
                touchedSchools.add(schoolId);
                record(out, stats, schoolId, regNo, field, "MIGRATED", oldChars, bytes.length, sha, rel, "");
            } catch (Exception e) {
                if (rel != null) fileStorageService.deleteQuietly(rel);
                record(out, stats, schoolId, regNo, field, "FAILED", oldChars, bytes.length, sha, "",
                        String.valueOf(e.getMessage()));
            }
            if (processed[0] % 50 == 0) {
                log.info("[media-migration] {} rows processed so far {}", processed[0], stats);
            }
        }
    }

    /** Same decoding the GET /{regNo}/photo endpoint applies to legacy rows. */
    private static byte[] decode(String stored) {
        String raw = stored.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        raw = raw.replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(BufferedWriter out, Map<String, Integer> stats, String schoolId, String regNo,
                               String field, String status, int oldChars, int bytes, String sha, String path,
                               String note) throws IOException {
        stats.merge(field + ":" + status, 1, Integer::sum);
        out.write(csv(schoolId) + "," + csv(regNo) + "," + field + "," + status + "," + oldChars + ","
                + bytes + "," + sha + "," + csv(path) + "," + csv(note) + "\n");
        out.flush();
    }

    private static String csv(String v) {
        return v == null ? "" : v.replaceAll("[,\\r\\n]", " ");
    }
}
