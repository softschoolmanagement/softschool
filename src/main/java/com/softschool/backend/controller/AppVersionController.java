package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Android app version control.
 *   GET  /api/app/version         public  - the Android app asks this when it opens: { version, forceUpdate, url }
 *   GET  /api/admin/app-version   super admin (AdminAuthFilter) - read the setting
 *   PUT  /api/admin/app-version   super admin - { version, forceUpdate }
 * The setting is kept in a small JSON file (no database change needed). Path: app.version-file, default ./app-version.json
 */
@RestController
public class AppVersionController {

    private static final String DOWNLOAD_URL = "https://softschoolmanagement.github.io/Android-Download/";
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(\\.[0-9]+){0,3}$");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public AppVersionController(@Value("${app.version-file:./app-version.json}") String path) {
        this.file = Paths.get(path);
    }

    private synchronized Map<String, Object> read() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "");
        m.put("forceUpdate", false);
        try {
            if (Files.exists(file)) {
                Map<?, ?> s = mapper.readValue(Files.readAllBytes(file), Map.class);
                if (s.get("version") != null) m.put("version", String.valueOf(s.get("version")));
                m.put("forceUpdate", Boolean.TRUE.equals(s.get("forceUpdate")));
            }
        } catch (Exception ignore) { /* unreadable file: behaves as "no update required" */ }
        return m;
    }

    @GetMapping("/api/app/version")
    public Map<String, Object> publicVersion() {
        Map<String, Object> m = read();
        m.put("url", DOWNLOAD_URL);
        return m;
    }

    @GetMapping("/api/admin/app-version")
    public Map<String, Object> adminGet() {
        Map<String, Object> m = read();
        m.put("url", DOWNLOAD_URL);
        return m;
    }

    @PutMapping("/api/admin/app-version")
    public synchronized ResponseEntity<?> adminSet(@RequestBody Map<String, Object> body) {
        String version = body.get("version") == null ? "" : String.valueOf(body.get("version")).trim();
        boolean force = Boolean.TRUE.equals(body.get("forceUpdate"));
        if (!version.isEmpty() && !VERSION.matcher(version).matches()) {
            return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("error", "Version must look like 1.0.8"));
        }
        if (force && version.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Collections.singletonMap("error", "Enter the version number first."));
        }
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", version);
            m.put("forceUpdate", force);
            Files.write(file, mapper.writeValueAsString(m).getBytes(StandardCharsets.UTF_8));
            m.put("url", DOWNLOAD_URL);
            return ResponseEntity.ok(m);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(java.util.Collections.singletonMap("error", "Could not save the setting on the server."));
        }
    }
}
