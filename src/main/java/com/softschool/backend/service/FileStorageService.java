package com.softschool.backend.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

    private String store(MultipartFile file, String subfolder, Set<String> allowedExt) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        String original = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot).toLowerCase();
        }
        if (!allowedExt.contains(ext)) {
            // Filename had no/odd extension (common for pasted/camera uploads
            // or canvas-compressed blobs) — fall back to the declared content type.
            ext = extensionFromContentType(file.getContentType(), allowedExt);
        }
        if (!allowedExt.contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: " + original);
        }

        // Random filename: never trust/reuse the client-supplied name, and
        // this is also what makes concurrent uploads collision-free.
        String filename = UUID.randomUUID() + ext;
        Path target = root.resolve(subfolder).resolve(filename).normalize();
        if (!target.startsWith(root)) {
            // Defensive — should be unreachable since filename is our own UUID,
            // but never allow writing outside the upload root.
            throw new SecurityException("Invalid upload path.");
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return subfolder + "/" + filename;
    }

    private String extensionFromContentType(String contentType, Set<String> allowedExt) {
        if (contentType == null) return "";
        switch (contentType) {
            case "image/jpeg": return allowedExt.contains(".jpg") ? ".jpg" : "";
            case "image/png":  return allowedExt.contains(".png") ? ".png" : "";
            case "image/gif":  return allowedExt.contains(".gif") ? ".gif" : "";
            case "image/webp": return allowedExt.contains(".webp") ? ".webp" : "";
            case "application/pdf": return allowedExt.contains(".pdf") ? ".pdf" : "";
            default: return "";
        }
    }

    /**
     * True when a Student.photo / Student.certData value looks like one of
     * OUR relative file paths ("photos/xxx.ext" or "bforms/xxx.ext") rather
     * than a legacy inline base64/data-URI value saved before this feature
     * existed. Lets the controller serve both old and new rows correctly
     * during the transition, with no data migration required.
     */
    public boolean isManagedPath(String stored) {
        return stored != null
                && !stored.startsWith("data:")
                && (stored.startsWith("photos/") || stored.startsWith("bforms/"));
    }

    /** Resolves a stored relative path to an absolute filesystem Path. */
    public Path resolve(String relativePath) {
        return root.resolve(relativePath).normalize();
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
            if (path.startsWith(root)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Non-fatal.
        }
    }
}
