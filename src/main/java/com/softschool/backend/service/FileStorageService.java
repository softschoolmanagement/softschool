package com.softschool.backend.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded student photos and B-Form/certificate files as real files
 * on disk, instead of as base64 text inside the students.photo /
 * students.certData LONGTEXT columns.
 *
 * Layout (relative to app.upload.dir, default "./uploads" — i.e. an
 * "uploads" folder created next to wherever the backend process is run
 * from):
 *
 *   uploads/
 *     photos/   <uuid>.<ext>   — student photos
 *     bforms/   <uuid>.<ext>   — B-Form / certificate scans (image or PDF)
 *
 * Only the RELATIVE path (e.g. "photos/3f1c...-9a.jpg") is ever written to
 * Student.photo / Student.certData — never an absolute filesystem path —
 * so the database stays portable across machines/environments and never
 * leaks server directory layout.
 *
 * AUTO-CREATION: init() runs via @PostConstruct, which Spring invokes once
 * this bean is constructed during application startup (it's already
 * @Autowired into StudentController, so it's always constructed). This is
 * what makes the "uploads/photos" and "uploads/bforms" folders appear
 * automatically the moment the backend runs, with no manual server setup.
 */
@Service
public class FileStorageService {

    // Configurable via application.properties:
    //   app.upload.dir=/absolute/or/relative/path
    // Defaults to a plain "uploads" folder relative to the working
    // directory the Spring Boot jar/process is started from.
    @Value("${app.upload.dir:uploads}")
    private String uploadDirProperty;

    private static final Set<String> ALLOWED_IMAGE_EXT =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private static final Set<String> ALLOWED_BFORM_EXT =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf");

    private Path root;

    @PostConstruct
    public void init() {
        try {
            root = Paths.get(uploadDirProperty).toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("photos"));
            Files.createDirectories(root.resolve("bforms"));
            Files.createDirectories(root.resolve("diary"));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not create upload directory at " + uploadDirProperty, e);
        }
    }

    /** Stores a student photo. Returns the relative path to save on Student.photo. */
    public String storePhoto(MultipartFile file) throws IOException {
        return store(file, "photos", ALLOWED_IMAGE_EXT);
    }

    /**
     * Stores a B-Form / certificate file (image or PDF). Returns the
     * relative path to save on Student.certData.
     */
    public String storeBform(MultipartFile file) throws IOException {
        return store(file, "bforms", ALLOWED_BFORM_EXT);
    }

    /** Stores one homework-diary picture. Returns the relative path ("diary/uuid.jpg"). */
    public String storeDiaryImage(MultipartFile file) throws IOException {
        return store(file, "diary", ALLOWED_IMAGE_EXT);
    }

    /** Hard cap per file, independent of the global multipart limit. */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** Only filenames this service itself generates are ever treated as managed. */
    private static final java.util.regex.Pattern MANAGED_PATH = java.util.regex.Pattern.compile(
            "^(photos|bforms|diary)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "\\.(jpg|png|gif|webp|pdf)$");

    private String store(MultipartFile file, String subfolder, Set<String> allowedExt) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large (max 10 MB).");
        }

        // The client-supplied filename and Content-Type are NOT trusted. The
        // type is decided from the file's own leading bytes, and the stored
        // extension is derived from that - so "evil.html" renamed to ".png",
        // or a script labelled image/png, is rejected.
        byte[] head = new byte[16];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        }
        String ext = detectExtension(head, read);
        if (ext == null || !allowedExt.contains(ext)) {
            throw new IllegalArgumentException("Unsupported or invalid file type.");
        }

        String filename = UUID.randomUUID() + ext;
        Path target = root.resolve(subfolder).resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("Invalid upload path.");
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return subfolder + "/" + filename;
    }

    /** Maps leading bytes to a canonical extension, or null if not an allowed image/PDF. */
    public static String detectExtension(byte[] b, int len) {
        if (b == null) return null;
        if (len >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return ".jpg";
        if (len >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return ".png";
        if (len >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return ".gif";
        if (len >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return ".webp";
        if (len >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return ".pdf";
        return null;
    }

    /** Safe Content-Type for the bytes, or null if they are not an allowed image/PDF. */
    public static String detectMediaType(byte[] bytes) {
        String ext = detectExtension(bytes, bytes == null ? 0 : Math.min(bytes.length, 16));
        if (ext == null) return null;
        switch (ext) {
            case ".jpg":  return "image/jpeg";
            case ".png":  return "image/png";
            case ".gif":  return "image/gif";
            case ".webp": return "image/webp";
            case ".pdf":  return "application/pdf";
            default:      return null;
        }
    }

    public boolean isManagedPath(String stored) {
        return stored != null && MANAGED_PATH.matcher(stored.trim()).matches();
    }

    public Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path is empty.");
        }

        // Values in the database are supposed to be paths returned by this
        // service.  Normalize separators before resolving so a malformed or
        // manually edited database value can never escape the upload root.
        String safeRelativePath = relativePath.trim().replace('\\', '/');
        Path resolved = root.resolve(safeRelativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Invalid stored file path.");
        }
        return resolved;
    }

    /**
     * Best-effort delete of a previously-stored file — used when a photo or
     * B-Form is replaced by a new upload, so old files don't accumulate on
     * disk forever. Never throws; an orphaned file left behind is harmless.
     */
    public void deleteQuietly(String relativePath) {
        if (!isManagedPath(relativePath)) return; // never touch legacy base64 values
        try {
            Path path = resolve(relativePath);
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Non-fatal.
        } catch (RuntimeException ignored) {
            // A malformed old database value must never make an update fail.
        }
    }
}
