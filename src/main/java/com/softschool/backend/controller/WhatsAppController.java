package com.softschool.backend.controller;

import com.softschool.backend.exception.WhatsAppBannedException;
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

    /**
     * Super-admin ban (school-specific or whole plan). Called first in every endpoint except status
     * (which reports the ban so the UI can hide the tab) and logout (so a school can still unlink).
     * Throws WhatsAppBannedException -> HTTP 403 {"error": "...", "code": "whatsapp_banned"}.
     */
    private void requireNotBanned(HttpServletRequest r) {
        String id = school(r);
        if (wa.isBanned(id)) throw new WhatsAppBannedException(id);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest r) {
        String id = school(r);
        if (wa.isBanned(id)) return Map.of("state", "banned", "connected", false, "banned", true);
        Map<String, Object> out = new java.util.HashMap<>(wa.status(id));
        out.put("config", wa.config(id));
        // fields the "Send to parents" popup shows
        Object lim = out.get("dailyLimit"), sent = out.get("sentToday"), queued = out.get("queued");
        if (lim instanceof Number l && sent instanceof Number s) {
            int q = queued instanceof Number n ? n.intValue() : 0;
            out.put("dailyCap", l.intValue());
            out.put("remainingToday", Math.max(0, l.intValue() - s.intValue() - q));
        }
        out.put("warmup", out.get("warmupDay") != null);
        out.put("quietNow", false);
        out.put("maxPerSend", WhatsAppService.MAX_PER_SEND);
        return out;
    }

    /** body: {} for QR mode, or {"phone":"03001234567"} for the 8-character pairing-code mode. */
    @PostMapping("/connect")
    public ResponseEntity<?> connect(HttpServletRequest r, @RequestBody(required = false) Map<String, String> body) {
        requireNotBanned(r);
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
        requireNotBanned(r);
        WhatsAppConfig c = wa.config(school(r));
        if (in.containsKey("enabled")) c.setEnabled(Boolean.TRUE.equals(in.get("enabled")));
        if (in.containsKey("notifyAbsent")) c.setNotifyAbsent(Boolean.TRUE.equals(in.get("notifyAbsent")));
        if (in.get("dailyLimit") instanceof Number n) {
            int v = n.intValue();
            c.setDailyLimit(v <= 0 ? null : Math.max(10, Math.min(v, 1000)));
            wa.pushLimit(school(r), c.getDailyLimit());
        }
        if (in.containsKey("notifyVoucher")) c.setNotifyVoucher(Boolean.TRUE.equals(in.get("notifyVoucher")));
        if (in.containsKey("notifyReminder")) c.setNotifyReminder(Boolean.TRUE.equals(in.get("notifyReminder")));
        if (in.get("reminderBeforeDays") instanceof String d) c.setReminderBeforeDays(cleanDays(d));
        if (in.get("reminderAfterDays") instanceof String d) c.setReminderAfterDays(cleanDays(d));
        if (in.containsKey("reminderOnDue")) c.setReminderOnDue(Boolean.TRUE.equals(in.get("reminderOnDue")));
        if (in.get("absentTemplate") instanceof String s) c.setAbsentTemplate(clip(s));
        if (in.get("voucherTemplate") instanceof String s) c.setVoucherTemplate(clip(s));
        if (in.get("reminderTemplate") instanceof String s) c.setReminderTemplate(clip(s));
        return configRepo.save(c);
    }

    @PostMapping("/test")
    public ResponseEntity<?> test(HttpServletRequest r, @RequestBody Map<String, String> in) {
        requireNotBanned(r);
        String phone = in.get("phone");
        if (phone == null || phone.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "phone is required"));
        String text = in.getOrDefault("text", "").isBlank()
                ? "Test message from your school management system. WhatsApp automation is working."
                : clip(in.get("text"));
        // Unique suffix: the gateway refuses the same text to the same number twice in 24 h ("duplicate").
        if (in.getOrDefault("text", "").isBlank()) text += " [" + java.time.LocalTime.now().withNano(0) + "]";
        List<Map<String, Object>> res = wa.sendRaw(school(r), List.of(Map.of("to", phone, "text", text)));
        if (res == null) return ResponseEntity.status(409).body(Map.of("error", "WhatsApp is not connected or the gateway is unreachable."));
        Map<String, Object> first = res.isEmpty() ? Map.of() : res.get(0);
        if (!Boolean.TRUE.equals(first.get("accepted"))) {
            return ResponseEntity.status(422).body(Map.of("error", "Not sent: " + friendly(String.valueOf(first.get("reason")))));
        }
        return ResponseEntity.ok(Map.of("ok", true, "sent", Boolean.TRUE.equals(first.get("sent"))));
    }

    private static String friendly(String reason) {
        return switch (reason) {
            case "duplicate" -> "the same text was already sent to this number in the last 24 hours.";
            case "recipient_cap" -> "this number already received the maximum messages for today.";
            case "number not on WhatsApp" -> "this number is not on WhatsApp.";
            case "invalid number" -> "the phone number is not valid.";
            case "send failed" -> "WhatsApp rejected the send, try again shortly.";
            default -> reason;
        };
    }

    /** body: {"className":"5","section":"A","text":"Dear parent of {student}, ..."}  (class/section optional) */
    @PostMapping("/broadcast")
    public ResponseEntity<?> broadcast(HttpServletRequest r, @RequestBody Map<String, String> in) {
        requireNotBanned(r);
        String text = in.get("text");
        if (text == null || text.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        String id = school(r);
        if (!wa.config(id).isOn()) return ResponseEntity.status(409).body(Map.of("error", "Turn WhatsApp automation on first."));
        return ResponseEntity.ok(wa.broadcast(id, in.get("className"), in.get("section"), clip(text)));
    }

    /** Popup data: classes, then (for a chosen class) sections and students with parent numbers and fee balance. */
    @GetMapping("/recipients")
    public Map<String, Object> recipients(HttpServletRequest r,
                                          @RequestParam(required = false) String className,
                                          @RequestParam(required = false) String section,
                                          @RequestParam(required = false) String monthKey) {
        requireNotBanned(r);
        return wa.recipients(school(r), className, section, monthKey);
    }

    /** kind = announcement | fee | attendance. body: {regNos:[...], className, section, text, monthKey, onlyUnpaid, days} */
    @PostMapping("/send/{kind}")
    public ResponseEntity<?> sendToParents(HttpServletRequest r, @PathVariable String kind,
                                           @RequestBody Map<String, Object> in) {
        requireNotBanned(r);
        if (!List.of("announcement", "fee", "attendance").contains(kind))
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown message type."));
        if (!wa.isConfigured()) return ResponseEntity.status(503).body(Map.of("error", "WhatsApp service is not configured on the server."));
        String id = school(r);
        if (!wa.config(id).isOn()) return ResponseEntity.status(409).body(Map.of("error", "Turn WhatsApp automation on first."));
        if (!(in.get("regNos") instanceof List<?> ids) || ids.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Choose at least one parent."));
        if (ids.size() > WhatsAppService.MAX_PER_SEND)
            return ResponseEntity.badRequest().body(Map.of("error", "Only " + WhatsAppService.MAX_PER_SEND + " parents can be messaged at one time."));
        if ("announcement".equals(kind)) {
            Object t = in.get("text");
            if (!(t instanceof String s) || s.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
            in.put("text", clip(s));
        }
        Map<String, Object> out = wa.sendToParents(id, kind, in);
        if (Boolean.FALSE.equals(out.get("gatewayOk")) && ((Number) out.get("queued")).intValue() == 0)
            return ResponseEntity.status(409).body(Map.of("error", "WhatsApp is not connected or the service is unreachable. Nothing was sent."));
        return ResponseEntity.ok(out);
    }

    /** "3, 1,1,x,99" -> "1,3": keeps only whole days 1..30, sorted, no duplicates. */
    private static String cleanDays(String csv) {
        java.util.TreeSet<Integer> days = new java.util.TreeSet<>();
        for (String p : csv.split(",")) {
            try { int d = Integer.parseInt(p.trim()); if (d >= 1 && d <= 30) days.add(d); }
            catch (NumberFormatException ignored) { }
        }
        return days.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static String clip(String s) { return s.length() > 1000 ? s.substring(0, 1000) : s; }
}
