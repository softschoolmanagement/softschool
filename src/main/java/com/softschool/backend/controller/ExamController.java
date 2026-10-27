package com.softschool.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.exception.ExamValidationException;
import com.softschool.backend.model.ExamDateSheetEntry;
import com.softschool.backend.model.ExamGrading;
import com.softschool.backend.model.ExamPaper;
import com.softschool.backend.model.ExamPaperResult;
import com.softschool.backend.repository.ExamDateSheetRepository;
import com.softschool.backend.repository.ExamGradingRepository;
import com.softschool.backend.repository.ExamPaperRepository;
import com.softschool.backend.repository.ExamPaperResultRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.service.ExamValidator;
import com.softschool.backend.service.PlanEnforcementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Examination module: question papers, result (marks) upload, date sheet and
 * the school's grading scheme.
 *
 *   GET    /api/exams/bootstrap            everything the Examination screens need, in one call
 *
 *   GET    /api/exams/papers               list (newest first)
 *   GET    /api/exams/papers/{id}
 *   PUT    /api/exams/papers/{id}          create or update (the browser chooses the id)
 *   DELETE /api/exams/papers/{id}          also deletes the paper's results
 *
 *   GET    /api/exams/results/{paperId}
 *   PUT    /api/exams/results/{paperId}    save draft / publish / unpublish
 *   DELETE /api/exams/results/{paperId}
 *
 *   GET    /api/exams/datesheet
 *   PUT    /api/exams/datesheet/{id}       create or update one line
 *   DELETE /api/exams/datesheet/{id}
 *
 *   GET    /api/exams/grading              {scale: [...]} or {scale: null} = built-in default
 *   PUT    /api/exams/grading
 *   DELETE /api/exams/grading              back to the default scale
 *
 *   POST   /api/exams/import               one-time upload of data an older browser kept locally
 *
 * SECURITY: "/api/exams" is a protected prefix in SchoolAuthFilter, so every
 * request already carries a valid school session and any schoolId in the
 * query/body matches it. The school used for every query below is read from
 * the verified session (a request attribute), never from client input.
 * Teacher sessions are refused by TeacherAccessGuard (not on its allow-list).
 *
 * All payloads are validated by ExamValidator before anything is saved.
 */
@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private static final int MAX_PAPERS = 1000;
    private static final int MAX_DATESHEET = 2000;

    @Autowired private ExamPaperRepository paperRepository;
    @Autowired private ExamPaperResultRepository resultRepository;
    @Autowired private ExamDateSheetRepository dateSheetRepository;
    @Autowired private ExamGradingRepository gradingRepository;
    @Autowired private PlanEnforcementService planEnforcementService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------------------------------------------------------- bootstrap

    @GetMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("papers", paperRepository.findBySchoolIdOrderByCreatedAtDescIdDesc(sid)
                .stream().map(this::paperView).collect(Collectors.toList()));
        Map<String, Object> results = new LinkedHashMap<>();
        for (ExamPaperResult r : resultRepository.findBySchoolId(sid)) results.put(r.getPaperId(), resultView(r));
        out.put("results", results);
        out.put("datesheet", dateSheetRepository.findBySchoolIdOrderByExamDateAscStartTimeAscIdAsc(sid)
                .stream().map(this::dateSheetView).collect(Collectors.toList()));
        out.put("grading", gradingRepository.findBySchoolId(sid).map(this::gradingView).orElse(null));
        return ResponseEntity.ok(out);
    }

    // ---------------------------------------------------------------- papers

    @GetMapping("/papers")
    public ResponseEntity<?> listPapers(HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        return ResponseEntity.ok(paperRepository.findBySchoolIdOrderByCreatedAtDescIdDesc(sid)
                .stream().map(this::paperView).collect(Collectors.toList()));
    }

    @GetMapping("/papers/{id}")
    public ResponseEntity<?> getPaper(@PathVariable String id, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(id, "paper");
        return paperRepository.findBySchoolIdAndPaperId(sid, id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(paperView(p)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "Paper not found."));
    }

    @Transactional
    @PutMapping("/papers/{id}")
    public ResponseEntity<?> savePaper(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(id, "paper");
        ExamValidator.Paper d = ExamValidator.paper(body);

        ExamPaper p = paperRepository.findBySchoolIdAndPaperId(sid, id).orElse(null);
        if (p == null) {
            if (paperRepository.countBySchoolId(sid) >= MAX_PAPERS) {
                throw new ExamValidationException("This school has reached the limit of " + MAX_PAPERS + " saved papers. Delete old papers first.");
            }
            p = new ExamPaper();
            p.setSchoolId(sid);
            p.setPaperId(id);
        }
        applyPaper(p, d);
        return ResponseEntity.ok(paperView(paperRepository.save(p)));
    }

    @Transactional
    @DeleteMapping("/papers/{id}")
    public ResponseEntity<?> deletePaper(@PathVariable String id, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(id, "paper");
        resultRepository.deleteBySchoolIdAndPaperId(sid, id);
        long removed = paperRepository.deleteBySchoolIdAndPaperId(sid, id);
        return removed > 0 ? ResponseEntity.noContent().build() : error(HttpStatus.NOT_FOUND, "Paper not found.");
    }

    // ---------------------------------------------------------------- results

    @GetMapping("/results/{paperId}")
    public ResponseEntity<?> getResult(@PathVariable String paperId, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(paperId, "paper");
        return resultRepository.findBySchoolIdAndPaperId(sid, paperId)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(resultView(r)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No results uploaded for this paper."));
    }

    @Transactional
    @PutMapping("/results/{paperId}")
    public ResponseEntity<?> saveResult(@PathVariable String paperId, @RequestBody JsonNode body, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(paperId, "paper");
        if (paperRepository.findBySchoolIdAndPaperId(sid, paperId).isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "Paper not found. Save the paper first.");
        }
        ExamValidator.Result d = ExamValidator.result(body);

        ExamPaperResult r = resultRepository.findBySchoolIdAndPaperId(sid, paperId).orElse(null);
        if (r != null && ExamPaperResult.PUBLISHED.equals(r.getStatus()) && ExamPaperResult.PUBLISHED.equals(d.status)) {
            // Published results are final. Re-sending the identical data (a retried request) is harmless;
            // changing it is not allowed until the results are unpublished.
            if (sameContent(r, d)) return ResponseEntity.ok(resultView(r));
            return error(HttpStatus.CONFLICT, "These results are published. Unpublish them before editing.");
        }
        if (r == null) {
            r = new ExamPaperResult();
            r.setSchoolId(sid);
            r.setPaperId(paperId);
        }
        applyResult(r, d);
        return ResponseEntity.ok(resultView(resultRepository.save(r)));
    }

    @Transactional
    @DeleteMapping("/results/{paperId}")
    public ResponseEntity<?> deleteResult(@PathVariable String paperId, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(paperId, "paper");
        long removed = resultRepository.deleteBySchoolIdAndPaperId(sid, paperId);
        return removed > 0 ? ResponseEntity.noContent().build() : error(HttpStatus.NOT_FOUND, "No results found for this paper.");
    }

    // ---------------------------------------------------------------- date sheet

    @GetMapping("/datesheet")
    public ResponseEntity<?> listDateSheet(HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        return ResponseEntity.ok(dateSheetRepository.findBySchoolIdOrderByExamDateAscStartTimeAscIdAsc(sid)
                .stream().map(this::dateSheetView).collect(Collectors.toList()));
    }

    @Transactional
    @PutMapping("/datesheet/{id}")
    public ResponseEntity<?> saveDateSheet(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(id, "date sheet entry");
        ExamValidator.DateSheet d = ExamValidator.dateSheet(body);

        ExamDateSheetEntry e = dateSheetRepository.findBySchoolIdAndEntryId(sid, id).orElse(null);
        if (e == null) {
            if (dateSheetRepository.countBySchoolId(sid) >= MAX_DATESHEET) {
                throw new ExamValidationException("The date sheet is full (" + MAX_DATESHEET + " entries). Remove old entries first.");
            }
            e = new ExamDateSheetEntry();
            e.setSchoolId(sid);
            e.setEntryId(id);
        }
        applyDateSheet(e, d);
        return ResponseEntity.ok(dateSheetView(dateSheetRepository.save(e)));
    }

    @Transactional
    @DeleteMapping("/datesheet/{id}")
    public ResponseEntity<?> deleteDateSheet(@PathVariable String id, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        ExamValidator.id(id, "date sheet entry");
        long removed = dateSheetRepository.deleteBySchoolIdAndEntryId(sid, id);
        return removed > 0 ? ResponseEntity.noContent().build() : error(HttpStatus.NOT_FOUND, "Date sheet entry not found.");
    }

    // ---------------------------------------------------------------- grading

    @GetMapping("/grading")
    public ResponseEntity<?> getGrading(HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        Map<String, Object> out = new LinkedHashMap<>();
        ExamGrading g = gradingRepository.findBySchoolId(sid).orElse(null);
        out.put("scale", g == null ? null : json(g.getScaleJson()));
        return ResponseEntity.ok(out);
    }

    @Transactional
    @PutMapping("/grading")
    public ResponseEntity<?> saveGrading(@RequestBody JsonNode body, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        String scale = ExamValidator.gradingScale(body);

        ExamGrading g = gradingRepository.findBySchoolId(sid).orElse(null);
        if (g == null) {
            g = new ExamGrading();
            g.setSchoolId(sid);
        }
        g.setScaleJson(scale);
        return ResponseEntity.ok(gradingView(gradingRepository.save(g)));
    }

    @Transactional
    @DeleteMapping("/grading")
    public ResponseEntity<?> resetGrading(HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        gradingRepository.deleteBySchoolId(sid);   // already-default is fine: the result is the same
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- one-time import

    /**
     * Uploads what an older version of the screen kept in the browser's
     * localStorage. Insert-if-missing only: nothing already on the server is
     * ever overwritten, and one bad record is skipped instead of failing the lot.
     * Body: {papers: [...], results: {paperId: {...}}, datesheet: [...], grading: {scale: [...]}}
     */
    @Transactional
    @PostMapping("/import")
    public ResponseEntity<?> importLocal(@RequestBody JsonNode body, HttpServletRequest req) {
        String sid = school(req);
        if (sid == null) return unauthorized();
        planEnforcementService.loadActiveSchool(sid);
        if (body == null || !body.isObject()) throw new ExamValidationException("A JSON object is required.");

        int[] ok = new int[4];        // papers, results, datesheet, grading
        int[] skipped = new int[4];

        // papers
        Set<String> paperIds = new HashSet<>();
        for (ExamPaper p : paperRepository.findBySchoolIdOrderByCreatedAtDescIdDesc(sid)) paperIds.add(p.getPaperId());
        long paperCount = paperIds.size();
        JsonNode papers = body.path("papers");
        if (papers.isArray()) {
            // Oldest first, so the newest ends up on top (the browser lists newest first).
            List<JsonNode> items = new ArrayList<>();
            papers.forEach(items::add);
            Collections.reverse(items);
            for (JsonNode item : items) {
                try {
                    String id = ExamValidator.id(item.path("id").asText(""), "paper");
                    if (paperIds.contains(id)) { skipped[0]++; continue; }
                    if (paperCount >= MAX_PAPERS) { skipped[0]++; continue; }
                    ExamPaper p = new ExamPaper();
                    p.setSchoolId(sid);
                    p.setPaperId(id);
                    applyPaper(p, ExamValidator.paper(item));
                    paperRepository.save(p);
                    paperIds.add(id);
                    paperCount++;
                    ok[0]++;
                } catch (ExamValidationException ex) {
                    skipped[0]++;
                }
            }
        }

        // results (only for papers that exist, and never over an existing result)
        JsonNode results = body.path("results");
        if (results.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = results.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                try {
                    String pid = ExamValidator.id(e.getKey(), "paper");
                    if (!paperIds.contains(pid) || resultRepository.findBySchoolIdAndPaperId(sid, pid).isPresent()) {
                        skipped[1]++;
                        continue;
                    }
                    ExamPaperResult r = new ExamPaperResult();
                    r.setSchoolId(sid);
                    r.setPaperId(pid);
                    applyResult(r, ExamValidator.result(e.getValue()));
                    resultRepository.save(r);
                    ok[1]++;
                } catch (ExamValidationException ex) {
                    skipped[1]++;
                }
            }
        }

        // date sheet
        long dsCount = dateSheetRepository.countBySchoolId(sid);
        JsonNode sheet = body.path("datesheet");
        if (sheet.isArray()) {
            for (JsonNode item : sheet) {
                try {
                    String id = ExamValidator.id(item.path("id").asText(""), "date sheet entry");
                    if (dsCount >= MAX_DATESHEET || dateSheetRepository.findBySchoolIdAndEntryId(sid, id).isPresent()) {
                        skipped[2]++;
                        continue;
                    }
                    ExamDateSheetEntry d = new ExamDateSheetEntry();
                    d.setSchoolId(sid);
                    d.setEntryId(id);
                    applyDateSheet(d, ExamValidator.dateSheet(item));
                    dateSheetRepository.save(d);
                    dsCount++;
                    ok[2]++;
                } catch (ExamValidationException ex) {
                    skipped[2]++;
                }
            }
        }

        // grading
        JsonNode grading = body.path("grading");
        if (grading.isObject()) {
            try {
                if (gradingRepository.findBySchoolId(sid).isPresent()) {
                    skipped[3]++;
                } else {
                    ExamGrading g = new ExamGrading();
                    g.setSchoolId(sid);
                    g.setScaleJson(ExamValidator.gradingScale(grading));
                    gradingRepository.save(g);
                    ok[3]++;
                }
            } catch (ExamValidationException ex) {
                skipped[3]++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", counts(ok));
        out.put("skipped", counts(skipped));
        return ResponseEntity.ok(out);
    }

    // ---------------------------------------------------------------- local error handling

    @ExceptionHandler(ExamValidationException.class)
    public ResponseEntity<?> onInvalid(ExamValidationException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Two requests created the same record at the same instant (unique key hit): ask the client to retry. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> onConflict(DataIntegrityViolationException ex) {
        return error(HttpStatus.CONFLICT, "That change collided with another one. Please try again.");
    }

    // ---------------------------------------------------------------- mapping

    private void applyPaper(ExamPaper p, ExamValidator.Paper d) {
        p.setTitle(d.title);
        p.setTerm(d.term);
        p.setClassName(d.className);
        p.setSectionName(d.section);
        p.setSubject(d.subject);
        p.setExamDate(d.date);
        p.setDurationMins(d.minutes);
        p.setInstructions(d.instructions);
        p.setTotalMarks(d.total);
        p.setSectionsJson(d.sectionsJson);
    }

    private void applyResult(ExamPaperResult r, ExamValidator.Result d) {
        r.setTotalMarks(d.total);
        r.setStatus(d.status);
        r.setMarksJson(d.marksJson);
    }

    private void applyDateSheet(ExamDateSheetEntry e, ExamValidator.DateSheet d) {
        e.setTerm(d.term);
        e.setClassName(d.className);
        e.setSubject(d.subject);
        e.setExamDate(d.date);
        e.setStartTime(d.start);
        e.setEndTime(d.end);
        e.setRoom(d.room);
    }

    private boolean sameContent(ExamPaperResult r, ExamValidator.Result d) {
        if (Math.abs(r.getTotalMarks() - d.total) > 1e-9) return false;
        return json(r.getMarksJson()).equals(json(d.marksJson));
    }

    // The JSON field names below are exactly what examination.js already uses.

    private Map<String, Object> paperView(ExamPaper p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getPaperId());
        m.put("title", p.getTitle());
        m.put("term", p.getTerm());
        m.put("cls", p.getClassName());
        m.put("section", nz(p.getSectionName()));
        m.put("subj", p.getSubject());
        m.put("date", nz(p.getExamDate()));
        m.put("mins", p.getDurationMins());
        m.put("instr", nz(p.getInstructions()));
        m.put("total", tidy(p.getTotalMarks()));
        m.put("at", p.getUpdatedAt().toEpochMilli());
        m.put("sections", json(p.getSectionsJson()));
        return m;
    }

    private Map<String, Object> resultView(ExamPaperResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("paperId", r.getPaperId());
        m.put("total", tidy(r.getTotalMarks()));
        m.put("status", r.getStatus());
        m.put("marks", json(r.getMarksJson()));
        m.put("at", r.getUpdatedAt().toEpochMilli());
        return m;
    }

    private Map<String, Object> dateSheetView(ExamDateSheetEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getEntryId());
        m.put("term", e.getTerm());
        m.put("cls", e.getClassName());
        m.put("subject", e.getSubject());
        m.put("date", e.getExamDate());
        m.put("start", e.getStartTime());
        m.put("end", e.getEndTime());
        m.put("room", nz(e.getRoom()));
        return m;
    }

    private Map<String, Object> gradingView(ExamGrading g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scale", json(g.getScaleJson()));
        return m;
    }

    private Map<String, Object> counts(int[] c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("papers", c[0]);
        m.put("results", c[1]);
        m.put("datesheet", c[2]);
        m.put("grading", c[3]);
        return m;
    }

    // ---------------------------------------------------------------- helpers

    /** The school of the verified session, set by SchoolAuthFilter. */
    private String school(HttpServletRequest req) {
        Object a = req.getAttribute(SchoolSessionService.SCHOOL_ID_ATTRIBUTE);
        return a instanceof String && !((String) a).isEmpty() ? (String) a : null;
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text == null ? "null" : text);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 90.0 -> 90 so the JSON matches what the browser would have computed itself. */
    private static Object tidy(Double v) {
        if (v == null) return 0;
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) v.longValue() : (Object) v;
    }

    private ResponseEntity<?> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED, "Please log in again.");
    }

    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
    }
}
