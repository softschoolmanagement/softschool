package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The "event image" shown to every user after the splash screen (website + Android app).
 *   GET    /api/app/event         public - { active, id }   (active=false when no image is set)
 *   GET    /api/app/event/image   public - the picture itself (original quality, no re-compression)
 *   GET    /api/admin/app-event   super admin - same as the public status
 *   PUT    /api/admin/app-event   super admin - { image: "data:image/...;base64,..." }
 *   DELETE /api/admin/app-event   super admin - removes it; nobody sees it afterwards
 * Stored as plain files (folder app.event-dir, default ./event-image) - no database change.
 */
@RestController
public class EventImageController {

    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;

    public EventImageController(@Value("${app.event-dir:./event-image}") String path) {
        this.dir = Paths.get(path);
    }

    private Path imgFile() { return dir.resolve("image.bin"); }
    private Path metaFile() { return dir.resolve("meta.json"); }

    private synchronized Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", false);
        try {
            if (Files.exists(imgFile()) && Files.exists(metaFile())) {
                Map<?, ?> s = mapper.readValue(Files.readAllBytes(metaFile()), Map.class);
                m.put("active", true);
                m.put("id", String.valueOf(s.get("id")));
            }
        } catch (Exception ignore) { m.put("active", false); }
        return m;
    }

    @GetMapping("/api/app/event")
    public Map<String, Object> status() { return meta(); }

    @GetMapping("/api/admin/app-event")
    public Map<String, Object> adminStatus() { return meta(); }

    @GetMapping("/api/app/event/image")
    public synchronized ResponseEntity<byte[]> image() {
        try {
            if (!Files.exists(imgFile()) || !Files.exists(metaFile())) return ResponseEntity.notFound().build();
            Map<?, ?> s = mapper.readValue(Files.readAllBytes(metaFile()), Map.class);
            String mime = String.valueOf(s.get("mime"));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                    .body(Files.readAllBytes(imgFile()));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/api/admin/app-event")
    public synchronized ResponseEntity<?> upload(@RequestBody Map<String, Object> body) {
        String data = body.get("image") == null ? "" : String.valueOf(body.get("image"));
        int comma = data.indexOf(',');
        if (!data.startsWith("data:image/") || comma < 0) return bad("Please choose an image file.");
        String mime = data.substring(5, data.indexOf(';') > 0 ? data.indexOf(';') : comma).toLowerCase();
        if (!mime.equals("image/jpeg") && !mime.equals("image/png") && !mime.equals("image/webp")) return bad("Use a JPG, PNG or WebP image.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(data.substring(comma + 1)); } catch (Exception e) { return bad("The image could not be read."); }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) return bad("The image must be smaller than 6 MB.");
        try {
            Files.createDirectories(dir);
            Files.write(imgFile(), bytes);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(System.currentTimeMillis()));
            m.put("mime", mime);
            Files.write(metaFile(), mapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.ok(meta());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Collections.singletonMap("error", "Could not save the image on the server."));
        }
    }

    @DeleteMapping("/api/admin/app-event")
    public synchronized ResponseEntity<?> remove() {
        try {
            Files.deleteIfExists(metaFile());
            Files.deleteIfExists(imgFile());
            return ResponseEntity.ok(meta());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Collections.singletonMap("error", "Could not remove the image."));
        }
    }

    private ResponseEntity<?> bad(String msg) { return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("error", msg)); }
}
