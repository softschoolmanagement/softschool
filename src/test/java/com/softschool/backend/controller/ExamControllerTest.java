package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.exception.ExamValidationException;
import com.softschool.backend.model.ExamDateSheetEntry;
import com.softschool.backend.model.ExamGrading;
import com.softschool.backend.model.ExamPaper;
import com.softschool.backend.model.ExamPaperResult;
import com.softschool.backend.model.School;
import com.softschool.backend.repository.ExamDateSheetRepository;
import com.softschool.backend.repository.ExamGradingRepository;
import com.softschool.backend.repository.ExamPaperRepository;
import com.softschool.backend.repository.ExamPaperResultRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.service.PlanEnforcementService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises ExamController's real logic (validation, tenant scoping, the
 * published-results lock, cascade delete, import) with in-memory stand-ins for
 * the four repositories. No Spring context and no database are needed.
 */
class ExamControllerTest {

    private final ObjectMapper om = new ObjectMapper();
    private ExamController c;

    // ------------------------------------------------------------------ in-memory repositories

    /** A tiny stateful fake for the derived-query repositories used by the exam module. */
    private static final class Store implements java.lang.reflect.InvocationHandler {
        final List<Object> rows = new ArrayList<>();
        long nextId = 1;

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
            String n = m.getName();
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return proxy == a[0];
            if (n.equals("toString")) return "fake-repo";
            if (n.equals("save")) {
                Object e = a[0];
                if (get(e, "getId") == null) { set(e, "setId", nextId++); rows.add(e); }
                else if (!rows.contains(e)) rows.add(e);
                return e;
            }
            if (n.startsWith("count")) return (long) match(n, a).size();
            if (n.startsWith("delete")) {
                List<Object> hit = match(n, a);
                rows.removeAll(hit);
                return (long) hit.size();
            }
            if (n.startsWith("find")) {
                List<Object> hit = match(n, a);
                if (n.contains("OrderByCreatedAtDesc")) Collections.reverse(hit);
                if (n.contains("OrderByExamDateAscStartTimeAsc")) {
                    hit.sort(Comparator.comparing((Object o) -> (String) get(o, "getExamDate")).thenComparing(o -> (String) get(o, "getStartTime")));
                }
                if (Optional.class.isAssignableFrom(m.getReturnType())) return hit.stream().findFirst();
                return hit;
            }
            throw new UnsupportedOperationException(n);
        }

        /** Handles findBySchoolId, findBySchoolIdAndPaperId, deleteBySchoolIdAndEntryId, countBySchoolId ... */
        private List<Object> match(String method, Object[] a) {
            String spec = method.replaceFirst("^(findBy|deleteBy|countBy)", "");
            int ob = spec.indexOf("OrderBy");
            if (ob >= 0) spec = spec.substring(0, ob);
            String[] props = spec.split("And");
            List<Object> out = new ArrayList<>();
            for (Object row : rows) {
                boolean ok = true;
                for (int i = 0; i < props.length; i++) {
                    if (!Objects.equals(get(row, "get" + props[i]), a[i])) { ok = false; break; }
                }
                if (ok) out.add(row);
            }
            return out;
        }

        private static Object get(Object o, String getter) {
            try { return o.getClass().getMethod(getter).invoke(o); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }

        private static void set(Object o, String setter, Object v) {
            try { o.getClass().getMethod(setter, Long.class).invoke(o, v); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Store s) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, s);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private final Store papers = new Store(), results = new Store(), sheet = new Store(), grading = new Store();

    @BeforeEach
    void setUp() throws Exception {
        c = new ExamController();
        inject(c, "paperRepository", fake(ExamPaperRepository.class, papers));
        inject(c, "resultRepository", fake(ExamPaperResultRepository.class, results));
        inject(c, "dateSheetRepository", fake(ExamDateSheetRepository.class, sheet));
        inject(c, "gradingRepository", fake(ExamGradingRepository.class, grading));
        inject(c, "planEnforcementService", new PlanEnforcementService() {
            @Override public School loadActiveSchool(String schoolId) { return new School(); }
        });
    }

    /** A request carrying the school a verified session would have put there. */
    private static HttpServletRequest as(String schoolId) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (p, m, a) -> m.getName().equals("getAttribute") && SchoolSessionService.SCHOOL_ID_ATTRIBUTE.equals(a[0]) ? schoolId : null);
    }

    private JsonNode j(String s) throws Exception { return om.readTree(s); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyMap(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    private static final String PAPER = "{\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"section\":\"\",\"subj\":\"English\",\"date\":\"2026-10-20\",\"mins\":90,"
            + "\"instr\":\"Write neatly.\",\"title\":\"English Mid\",\"total\":1,\"at\":1,\"sections\":[{\"type\":\"MCQs\",\"attempt\":0,\"marks\":1,"
            + "\"questions\":[{\"text\":\"Q1\",\"opts\":[\"a\",\"b\",\"c\",\"d\"]},{\"text\":\"Q2\",\"opts\":[\"a\",\"b\",\"c\",\"d\"]}]},"
            + "{\"type\":\"Long Questions\",\"attempt\":0,\"marks\":5,\"questions\":[{\"text\":\"L1\",\"opts\":[]}]}]}";

    // ------------------------------------------------------------------ tests

    @Test
    void requestWithoutAVerifiedSessionIsRefused() {
        assertEquals(401, c.bootstrap(as(null)).getStatusCode().value());
        assertEquals(401, c.listPapers(as("")).getStatusCode().value());
    }

    @Test
    void emptySchoolBootstrapsToEmptyCollections() {
        Map<String, Object> b = bodyMap(c.bootstrap(as("S1")));
        assertEquals(0, ((List<?>) b.get("papers")).size());
        assertEquals(0, ((Map<?, ?>) b.get("results")).size());
        assertEquals(0, ((List<?>) b.get("datesheet")).size());
        assertTrue(b.containsKey("grading"));
        assertNull(b.get("grading"));
    }

    @Test
    void paperRoundTripUsesTheFieldNamesTheScreenExpects() throws Exception {
        ResponseEntity<?> saved = c.savePaper("xp1", j(PAPER), as("S1"));
        assertEquals(200, saved.getStatusCode().value());
        Map<String, Object> v = bodyMap(saved);
        assertEquals("xp1", v.get("id"));
        assertEquals("English Mid", v.get("title"));
        assertEquals("Class 5", v.get("cls"));
        assertEquals("English", v.get("subj"));
        assertEquals(90, v.get("mins"));
        assertEquals(7L, v.get("total"));            // 2x1 + 1x5, the client's "total":1 is ignored
        assertTrue(v.get("sections") instanceof JsonNode);
        assertEquals(2, ((JsonNode) v.get("sections")).size());
        assertTrue(((Number) v.get("at")).longValue() > 0);

        // update in place: same id, still one row
        c.savePaper("xp1", j(PAPER.replace("English Mid", "English Mid v2")), as("S1"));
        List<?> list = (List<?>) bodyMap(c.bootstrap(as("S1"))).get("papers");
        assertEquals(1, list.size());
        assertEquals("English Mid v2", ((Map<?, ?>) list.get(0)).get("title"));
    }

    @Test
    void invalidPapersAndIdsAreRejected() {
        assertThrows(ExamValidationException.class, () -> c.savePaper("bad id!", j(PAPER), as("S1")));
        assertThrows(ExamValidationException.class, () -> c.savePaper("xp1", j("{\"term\":\"T\"}"), as("S1")));
        assertEquals(0, papers.rows.size());
    }

    @Test
    void schoolsCannotSeeOrTouchEachOthersData() throws Exception {
        c.savePaper("xp1", j(PAPER), as("S1"));
        c.saveResult("xp1", j("{\"total\":7,\"status\":\"draft\",\"marks\":{\"R1\":\"5\"}}"), as("S1"));

        assertEquals(0, ((List<?>) bodyMap(c.bootstrap(as("S2"))).get("papers")).size());
        assertEquals(404, c.getPaper("xp1", as("S2")).getStatusCode().value());
        assertEquals(404, c.getResult("xp1", as("S2")).getStatusCode().value());
        // S2 cannot write results against S1's paper id, nor delete it
        assertEquals(404, c.saveResult("xp1", j("{\"total\":7,\"marks\":{\"R1\":\"1\"}}"), as("S2")).getStatusCode().value());
        assertEquals(404, c.deletePaper("xp1", as("S2")).getStatusCode().value());
        assertEquals(1, papers.rows.size());
        // S2 may reuse the same paper id for its own paper without touching S1's
        c.savePaper("xp1", j(PAPER.replace("English Mid", "S2 paper")), as("S2"));
        assertEquals("English Mid", bodyMap(c.getPaper("xp1", as("S1"))).get("title"));
        assertEquals("S2 paper", bodyMap(c.getPaper("xp1", as("S2"))).get("title"));
    }

    @Test
    void resultWorkflowDraftPublishLockUnpublish() throws Exception {
        assertEquals(404, c.saveResult("nope", j("{\"total\":7,\"marks\":{\"R1\":\"5\"}}"), as("S1")).getStatusCode().value());
        c.savePaper("xp1", j(PAPER), as("S1"));

        // draft
        Map<String, Object> d = bodyMap(c.saveResult("xp1", j("{\"total\":7,\"status\":\"draft\",\"marks\":{\"R1\":\"5\",\"R2\":\"A\"}}"), as("S1")));
        assertEquals("draft", d.get("status"));
        assertEquals(7L, d.get("total"));
        assertEquals("5", ((JsonNode) d.get("marks")).get("R1").asText());

        // marks above the maximum are refused
        assertThrows(ExamValidationException.class, () -> c.saveResult("xp1", j("{\"total\":7,\"marks\":{\"R1\":\"8\"}}"), as("S1")));

        // publish
        assertEquals("published", bodyMap(c.saveResult("xp1", j("{\"total\":7,\"status\":\"published\",\"marks\":{\"R1\":\"5\",\"R2\":\"A\"}}"), as("S1"))).get("status"));
        // a retried identical publish is harmless
        assertEquals(200, c.saveResult("xp1", j("{\"total\":7,\"status\":\"published\",\"marks\":{\"R2\":\"A\",\"R1\":\"5\"}}"), as("S1")).getStatusCode().value());
        // but changing published marks is locked
        ResponseEntity<?> locked = c.saveResult("xp1", j("{\"total\":7,\"status\":\"published\",\"marks\":{\"R1\":\"6\"}}"), as("S1"));
        assertEquals(409, locked.getStatusCode().value());
        assertEquals("5", ((JsonNode) bodyMap(c.getResult("xp1", as("S1"))).get("marks")).get("R1").asText());

        // unpublish (the screen sends status draft with the same marks), then edit
        assertEquals("draft", bodyMap(c.saveResult("xp1", j("{\"total\":7,\"status\":\"draft\",\"marks\":{\"R1\":\"5\",\"R2\":\"A\"}}"), as("S1"))).get("status"));
        assertEquals("6", ((JsonNode) bodyMap(c.saveResult("xp1", j("{\"total\":7,\"status\":\"draft\",\"marks\":{\"R1\":\"6\"}}"), as("S1"))).get("marks")).get("R1").asText());
        assertEquals(1, results.rows.size());
    }

    @Test
    void deletingAPaperAlsoDeletesItsResults() throws Exception {
        c.savePaper("xp1", j(PAPER), as("S1"));
        c.savePaper("xp2", j(PAPER), as("S1"));
        c.saveResult("xp1", j("{\"total\":7,\"marks\":{\"R1\":\"5\"}}"), as("S1"));
        c.saveResult("xp2", j("{\"total\":7,\"marks\":{\"R1\":\"4\"}}"), as("S1"));

        assertEquals(204, c.deletePaper("xp1", as("S1")).getStatusCode().value());
        assertEquals(404, c.getResult("xp1", as("S1")).getStatusCode().value());
        assertEquals(200, c.getResult("xp2", as("S1")).getStatusCode().value());
        assertEquals(404, c.deletePaper("xp1", as("S1")).getStatusCode().value());   // already gone
        assertEquals(1, papers.rows.size());
        assertEquals(1, results.rows.size());
    }

    @Test
    void dateSheetCrudAndOrdering() throws Exception {
        String e1 = "{\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"subject\":\"Math\",\"date\":\"2026-10-22\",\"start\":\"09:00\",\"end\":\"11:00\",\"room\":\"\"}";
        String e2 = "{\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"subject\":\"English\",\"date\":\"2026-10-20\",\"start\":\"09:00\",\"end\":\"11:00\",\"room\":\"Hall 1\"}";
        assertEquals(200, c.saveDateSheet("d1", j(e1), as("S1")).getStatusCode().value());
        c.saveDateSheet("d2", j(e2), as("S1"));
        List<?> list = (List<?>) bodyMap(c.bootstrap(as("S1"))).get("datesheet");
        assertEquals("d2", ((Map<?, ?>) list.get(0)).get("id"));      // earliest date first
        assertEquals("Hall 1", ((Map<?, ?>) list.get(0)).get("room"));

        assertThrows(ExamValidationException.class, () -> c.saveDateSheet("d3", j(e1.replace("\"end\":\"11:00\"", "\"end\":\"08:00\"")), as("S1")));
        c.saveDateSheet("d1", j(e1.replace("Math", "Maths")), as("S1"));   // edit
        assertEquals(2, sheet.rows.size());
        assertEquals(204, c.deleteDateSheet("d1", as("S1")).getStatusCode().value());
        assertEquals(404, c.deleteDateSheet("d1", as("S1")).getStatusCode().value());
        assertEquals(404, c.deleteDateSheet("d2", as("S2")).getStatusCode().value());   // other school
        assertEquals(1, sheet.rows.size());
    }

    @Test
    void gradingSaveReadReset() throws Exception {
        assertNull(bodyMap(c.getGrading(as("S1"))).get("scale"));
        ResponseEntity<?> saved = c.saveGrading(j("{\"scale\":[{\"min\":0,\"grade\":\"F\",\"remark\":\"Fail\",\"pass\":false},{\"min\":40,\"grade\":\"P\",\"remark\":\"Pass\",\"pass\":true}]}"), as("S1"));
        assertEquals(200, saved.getStatusCode().value());
        JsonNode scale = (JsonNode) bodyMap(c.getGrading(as("S1"))).get("scale");
        assertEquals("P", scale.get(0).get("grade").asText());
        assertNull(bodyMap(c.getGrading(as("S2"))).get("scale"));
        assertThrows(ExamValidationException.class, () -> c.saveGrading(j("{\"scale\":[{\"min\":10,\"grade\":\"A\",\"pass\":true}]}"), as("S1")));
        assertEquals(204, c.resetGrading(as("S1")).getStatusCode().value());
        assertNull(bodyMap(c.getGrading(as("S1"))).get("scale"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importUploadsOldBrowserDataOnceAndSkipsBadRecords() throws Exception {
        String payload = "{\"papers\":[" + PAPER.replace("{\"term\"", "{\"id\":\"newer\",\"term\"") + ","
                + PAPER.replace("{\"term\"", "{\"id\":\"older\",\"term\"") + ","
                + "{\"id\":\"broken\",\"term\":\"T\"}],"
                + "\"results\":{\"older\":{\"total\":7,\"status\":\"published\",\"marks\":{\"R1\":\"6\"}},"
                + "\"ghost\":{\"total\":7,\"marks\":{\"R1\":\"6\"}}},"
                + "\"datesheet\":[{\"id\":\"d1\",\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"subject\":\"Math\",\"date\":\"2026-10-22\",\"start\":\"09:00\",\"end\":\"11:00\"},"
                + "{\"id\":\"d2\",\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"subject\":\"Math\",\"date\":\"bad\",\"start\":\"09:00\",\"end\":\"11:00\"}],"
                + "\"grading\":{\"scale\":[{\"min\":0,\"grade\":\"F\",\"pass\":false},{\"min\":50,\"grade\":\"P\",\"pass\":true}]}}";

        Map<String, Object> out = bodyMap(c.importLocal(j(payload), as("S1")));
        Map<String, Object> imp = (Map<String, Object>) out.get("imported"), skp = (Map<String, Object>) out.get("skipped");
        assertEquals(2, imp.get("papers"));
        assertEquals(1, skp.get("papers"));
        assertEquals(1, imp.get("results"));
        assertEquals(1, skp.get("results"));       // "ghost" has no paper
        assertEquals(1, imp.get("datesheet"));
        assertEquals(1, skp.get("datesheet"));
        assertEquals(1, imp.get("grading"));

        // newest-first order is preserved (the screen's first entry was the newest)
        List<?> list = (List<?>) bodyMap(c.bootstrap(as("S1"))).get("papers");
        assertEquals("newer", ((Map<?, ?>) list.get(0)).get("id"));

        // importing again changes nothing
        Map<String, Object> again = bodyMap(c.importLocal(j(payload), as("S1")));
        Map<String, Object> imp2 = (Map<String, Object>) again.get("imported");
        assertEquals(0, imp2.get("papers"));
        assertEquals(0, imp2.get("results"));
        assertEquals(0, imp2.get("datesheet"));
        assertEquals(0, imp2.get("grading"));
        assertEquals(2, papers.rows.size());
    }

    @Test
    void entitiesExposeTheStoredFields() {
        ExamPaper p = new ExamPaper();
        p.setPaperId("a");
        assertEquals("a", p.getPaperId());
        assertEquals(ExamPaperResult.DRAFT, new ExamPaperResult().getStatus());
        assertNotNull(new ExamDateSheetEntry().getCreatedAt());
        assertNull(new ExamGrading().getScaleJson());
    }
}
