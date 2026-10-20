package com.softschool.backend.controller;

import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.model.ParentSubmission;
import com.softschool.backend.repository.ParentSubmissionRepository;
import com.softschool.backend.service.FileStorageService;
import com.softschool.backend.service.ParentContext;
import com.softschool.backend.service.ParentUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Feedback, leave applications, complaints and suggestions sent from the parent portal.
 *
 *   POST /api/parent/submissions   multipart/form-data
 *        kind      feedback | leave | complaint | suggestion       (default feedback)
 *        regNo     the child it is about (required for leave / complaint / suggestion)
 *        category  (feedback) the category chosen on the page
 *        title     optional
 *        content   the text            (also accepted as "body" / "text")
 *        from, to  yyyy-MM-dd          (leave only)
 *        method    write | image | camera   (leave only)
 *        images    0..3 pictures
 *   GET  /api/parent/submissions[?kind=]   this parent's own submissions
 *
 * The school reads and answers them through ParentAdminController. The feedback page already posts
 * multipart FormData with category/title/content/images; the three application forms currently keep
 * their picture as a data URI, so they must send it as a file (FormData) instead.
 */
@RestController
@RequestMapping("/api/parent")
public class ParentSubmissionController {

    private static final Set<String> KINDS = new HashSet<>(Arrays.asList("feedback", "leave", "complaint", "suggestion"));
    private static final int MAX_IMAGES = 3;
    private static final long MAX_IMAGE_BYTES = 3L * 1024 * 1024;   // 3 x 3 MB fits the 10 MB request cap in application.properties
    private static final int PARENT_DAILY_LIMIT = 15;
    private static final int MAX_LEAVE_DAYS = 60;

    @Autowired private ParentContext parentContext;
    @Autowired private ParentSubmissionRepository submissionRepository;
    @Autowired private FileStorageService fileStorageService;

    @PostMapping(value = "/submissions", consumes = "multipart/form-data")
    @Transactional
    public ResponseEntity<?> create(@RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String regNo,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String title,
                                    @RequestParam(required = false) String content,
                                    @RequestParam(required = false) String body,
                                    @RequestParam(required = false) String text,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String method,
                                    @RequestParam(value = "images", required = false) List<MultipartFile> images,
                                    HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");

        String k = kind == null || kind.isBlank() ? "feedback" : kind.trim().toLowerCase();
        if (!KINDS.contains(k)) return ParentUtil.error(HttpStatus.BAD_REQUEST, "kind must be feedback, leave, complaint or suggestion.");

        ParentChildDTO child = null;
        if (regNo != null && !regNo.isBlank()) {
            child = ctx.child(regNo);
            if (child == null) return ParentUtil.error(HttpStatus.FORBIDDEN, "Not allowed.");
        } else if (!"feedback".equals(k)) {
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "Choose which child this is about.");
        }

        String msg = ParentUtil.clip(content != null ? content : body != null ? body : text, 2000);
        List<MultipartFile> files = new ArrayList<>();
        if (images != null) for (MultipartFile f : images) if (f != null && !f.isEmpty()) files.add(f);
        if (msg.isEmpty() && files.isEmpty()) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Write your message or attach a picture.");
        if ("feedback".equals(k) && msg.length() < 10) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Please write a little more (at least 10 characters).");

        String fromDate = null, toDate = null;
        if ("leave".equals(k)) {
            try {
                LocalDate f = LocalDate.parse(from == null ? "" : from.trim());
                LocalDate t = LocalDate.parse(to == null ? "" : to.trim());
                if (t.isBefore(f)) return ParentUtil.error(HttpStatus.BAD_REQUEST, "The leave end date is before the start date.");
                if (ChronoUnit.DAYS.between(f, t) > MAX_LEAVE_DAYS) return ParentUtil.error(HttpStatus.BAD_REQUEST, "A leave application can cover at most " + MAX_LEAVE_DAYS + " days.");
                fromDate = f.toString();
                toDate = t.toString();
            } catch (Exception e) {
                return ParentUtil.error(HttpStatus.BAD_REQUEST, "Choose the leave duration (From and To dates).");
            }
        }

        if (files.size() > MAX_IMAGES) return ParentUtil.error(HttpStatus.BAD_REQUEST, "You can attach up to " + MAX_IMAGES + " pictures.");
        for (MultipartFile f : files) {
            String type = f.getContentType() == null ? "" : f.getContentType().toLowerCase();
            if (!type.startsWith("image/")) return ParentUtil.error(HttpStatus.BAD_REQUEST, "Only pictures can be attached.");
            if (f.getSize() > MAX_IMAGE_BYTES) return ParentUtil.error(HttpStatus.PAYLOAD_TOO_LARGE, "Each picture must be under 3 MB.");
        }

        long today = submissionRepository.countBySchoolIdAndPhoneAndCreatedAtAfter(ctx.schoolId, ctx.phone, Instant.now().minus(1, ChronoUnit.DAYS));
        if (today >= PARENT_DAILY_LIMIT) return ParentUtil.error(HttpStatus.TOO_MANY_REQUESTS, "Daily limit reached. Try again tomorrow.");

        List<String> stored = new ArrayList<>();
        try {
            // FileStorageService has one image store (the diary folder); parent pictures live there too.
            for (MultipartFile f : files) stored.add(fileStorageService.storeDiaryImage(f));
        } catch (IllegalArgumentException e) {
            for (String s : stored) fileStorageService.deleteQuietly(s);          // all or nothing
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "Could not save a picture: " + e.getMessage());
        } catch (IOException | SecurityException e) {
            for (String s : stored) fileStorageService.deleteQuietly(s);
            return ParentUtil.error(HttpStatus.BAD_REQUEST, "Could not save a picture.");
        }

        ParentSubmission s = new ParentSubmission();
        s.setSchoolId(ctx.schoolId);
        s.setPhone(ctx.phone);
        s.setKind(k);
        s.setCategory(category == null || category.isBlank() ? null : ParentUtil.clip(category, 60));
        s.setTitle(title == null || title.isBlank() ? null : ParentUtil.clip(title, 200));
        s.setBody(msg.isEmpty() ? null : msg);
        s.setFromDate(fromDate);
        s.setToDate(toDate);
        s.setMethod(method == null || method.isBlank() ? null : ParentUtil.clip(method, 20));
        if (child != null) {
            s.setStudentRegNo(child.getRegNo());
            s.setStudentName(child.getFullName());
            s.setClassName(ParentUtil.classLabel(child));
            s.setGuardianName(child.getGuardianName());
        }
        s.setImagePaths(stored.isEmpty() ? null : String.join("\n", stored));
        submissionRepository.save(s);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(s));
    }

    @GetMapping("/submissions")
    public ResponseEntity<?> mine(@RequestParam(required = false) String kind, HttpServletRequest req) {
        ParentContext.Ctx ctx = parentContext.resolve(req);
        if (ctx == null) return ParentUtil.error(HttpStatus.UNAUTHORIZED, "Please log in again.");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParentSubmission s : submissionRepository.findBySchoolIdAndPhoneOrderByCreatedAtDesc(ctx.schoolId, ctx.phone, PageRequest.of(0, 100))) {
            if (kind != null && !kind.isBlank() && !kind.trim().equalsIgnoreCase(s.getKind())) continue;
            out.add(view(s));
        }
        return ResponseEntity.ok(out);
    }

    /** Shared with ParentAdminController. */
    static Map<String, Object> view(ParentSubmission s) {
        int count = s.getImagePaths() == null || s.getImagePaths().isBlank() ? 0 : s.getImagePaths().split("\n").length;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("kind", s.getKind());
        m.put("category", s.getCategory());
        m.put("title", s.getTitle());
        m.put("body", s.getBody());
        m.put("from", s.getFromDate());
        m.put("to", s.getToDate());
        m.put("method", s.getMethod());
        m.put("regNo", s.getStudentRegNo());
        m.put("studentName", s.getStudentName());
        m.put("className", s.getClassName());
        m.put("guardianName", s.getGuardianName());
        m.put("imageCount", count);
        m.put("status", s.getStatus());
        m.put("adminNote", s.getAdminNote());
        m.put("createdAt", s.getCreatedAt().toString());
        return m;
    }
}
