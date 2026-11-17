package com.softschool.backend.controller;

import com.softschool.backend.model.WhatsAppConfig;
import com.softschool.backend.repository.WhatsAppConfigRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.service.WhatsAppService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Settings > WhatsApp tab. Admin-only: SchoolAuthFilter protects /api/whatsapp and the teacher guard
 * denies teacher sessions by default. The school is ALWAYS taken from the signed session, never from
 * the request, so one school can never link, read or message through another school's number.
 */
@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppController {

    @Autowired private WhatsAppService wa;
    @Autowired private WhatsAppConfigRepository configRepo;

    private static String school(HttpServletRequest r) {
        return (String) r.getAttribute(SchoolSessionService.SCHOOL_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest r) {
        String id = school(r);
        Map<String, Object> out = new java.util.HashMap<>(wa.status(id));
        out.put("config", wa.config(id));
        return out;
    }

    /** body: {} for QR mode, or {"phone":"03001234567"} for the 8-character pairing-code mode. */
    @PostMapping("/connect")
    public ResponseEntity<?> connect(HttpServletRequest r, @RequestBody(required = false) Map<String, String> body) {
        if (!wa.isConfigured()) return ResponseEntity.status(503).body(Map.of("error", "WhatsApp service is not configured on the server."));
        try {
            return ResponseEntity.ok(wa.connect(school(r), body == null ? null : body.get("phone")));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "WhatsApp service is unreachable."));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest r) {
        try { wa.logout(school(r)); } catch (Exception ignored) { /* nothing linked */ }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/config")
    public WhatsAppConfig saveConfig(HttpServletRequest r, @RequestBody Map<String, Object> in) {
        WhatsAppConfig c = wa.config(school(r));
        if (in.containsKey("enabled")) c.setEnabled(Boolean.TRUE.equals(in.get("enabled")));
        if (in.containsKey("notifyAbsent")) c.setNotifyAbsent(Boolean.TRUE.equals(in.get("notifyAbsent")));
        if (in.containsKey("notifyFeePaid")) c.setNotifyFeePaid(Boolean.TRUE.equals(in.get("notifyFeePaid")));
        if (in.get("absentTemplate") instanceof String s) c.setAbsentTemplate(clip(s));
        if (in.get("feeTemplate") instanceof String s) c.setFeeTemplate(clip(s));
        return configRepo.save(c);
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(HttpServletRequest r, @RequestBody Map<String, String> in) {
        String phone = in.get("phone");
        if (phone == null || phone.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "phone is required"));
        String text = in.getOrDefault("text", "").isBlank()
                ? "Test message from your school management system. WhatsApp automation is working."
                : clip(in.get("text"));
        boolean ok = wa.send(school(r), List.of(Map.of("to", phone, "text", text)));
        return ok ? ResponseEntity.ok(Map.of("ok", true))
                  : ResponseEntity.status(409).body(Map.of("error", "WhatsApp is not connected."));
    }

    /** body: {"className":"5","section":"A","text":"Dear parent of {student}, ..."}  (class/section optional) */
    @PostMapping("/broadcast")
    public ResponseEntity<?> broadcast(HttpServletRequest r, @RequestBody Map<String, String> in) {
        String text = in.get("text");
        if (text == null || text.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        String id = school(r);
        if (!wa.config(id).isOn()) return ResponseEntity.status(409).body(Map.of("error", "Turn WhatsApp automation on first."));
        return ResponseEntity.ok(wa.broadcast(id, in.get("className"), in.get("section"), clip(text)));
    }

    private static String clip(String s) { return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
