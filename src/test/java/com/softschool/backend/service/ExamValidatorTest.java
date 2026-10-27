package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.exception.ExamValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests: no Spring context, no database. */
class ExamValidatorTest {

    private final ObjectMapper om = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String msg(Runnable r) {
        return assertThrows(ExamValidationException.class, r::run).getMessage();
    }

    // ---------- ids ----------

    @Test
    void idAcceptsBrowserIdsAndRejectsJunk() {
        assertEquals("xlq3k9a1b2", ExamValidator.id("xlq3k9a1b2", "paper"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.id("../etc", "paper"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.id("a b", "paper"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.id("", "paper"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.id(null, "paper"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.id("x".repeat(41), "paper"));
    }

    // ---------- paper ----------

    private static final String PAPER = "{\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"section\":\"A\",\"subj\":\"English\","
            + "\"date\":\"2026-10-20\",\"mins\":90,\"instr\":\"Write neatly.\",\"total\":9999,\"sections\":["
            + "{\"type\":\"MCQs\",\"attempt\":0,\"marks\":1,\"questions\":["
            + "{\"text\":\"Q1\",\"opts\":[\"a\",\"b\",\"c\",\"d\"]},{\"text\":\"Q2\",\"opts\":[\"a\",\"b\",\"c\",\"d\"]},{\"text\":\"  \",\"opts\":[\"\",\"\",\"\",\"\"]}]},"
            + "{\"type\":\"Long Questions\",\"attempt\":\"2\",\"marks\":5,\"questions\":["
            + "{\"text\":\"L1\",\"opts\":[]},{\"text\":\"L2\",\"opts\":[]},{\"text\":\"L3\",\"opts\":[]}]}]}";

    @Test
    void paperTotalIsRecomputedFromSectionsNotTrustedFromClient() {
        ExamValidator.Paper p = ExamValidator.paper(json(PAPER));
        // MCQs: 2 filled x 1 = 2.  Long: attempt any 2 of 3 x 5 = 10.  Client claimed 9999.
        assertEquals(12.0, p.total, 1e-9);
        assertEquals("English", p.subject);
        assertEquals("Class 5", p.className);
        assertEquals(90, p.minutes);
        assertEquals("English \u2014 Mid Term (Class 5 (A))", p.title);
        JsonNode secs = json(p.sectionsJson);
        assertEquals(2, secs.size());
        assertEquals("MCQs", secs.get(0).get("type").asText());
        assertEquals(2, secs.get(1).get("attempt").asInt());
        assertEquals(4, secs.get(0).get("questions").get(0).get("opts").size());
    }

    @Test
    void paperNeedsAtLeastOneQuestionAndRequiredFields() {
        String empty = "{\"term\":\"Mid Term\",\"cls\":\"C\",\"subj\":\"S\",\"sections\":[{\"type\":\"MCQs\",\"attempt\":0,\"marks\":1,\"questions\":[{\"text\":\" \",\"opts\":[]}]}]}";
        assertEquals("Write at least one question.", msg(() -> ExamValidator.paper(json(empty))));
        assertTrue(msg(() -> ExamValidator.paper(json("{\"cls\":\"C\",\"subj\":\"S\",\"sections\":[]}"))).contains("Exam / Term"));
        assertTrue(msg(() -> ExamValidator.paper(json("{\"term\":\"T\",\"subj\":\"S\",\"sections\":[]}"))).contains("Class"));
        assertThrows(ExamValidationException.class, () -> ExamValidator.paper(json("[]")));
    }

    @Test
    void paperRejectsBadDatesAndSizes() {
        String base = "\"term\":\"T\",\"cls\":\"C\",\"subj\":\"S\",\"sections\":[{\"type\":\"MCQs\",\"marks\":1,\"questions\":[{\"text\":\"q\",\"opts\":[]}]}]";
        assertTrue(msg(() -> ExamValidator.paper(json("{" + base + ",\"date\":\"20-10-2026\"}"))).contains("valid date"));
        assertTrue(msg(() -> ExamValidator.paper(json("{" + base + ",\"mins\":0}"))).contains("between"));
        String longQ = "{\"term\":\"T\",\"cls\":\"C\",\"subj\":\"S\",\"sections\":[{\"type\":\"MCQs\",\"marks\":1,\"questions\":[{\"text\":\"" + "x".repeat(5001) + "\",\"opts\":[]}]}]}";
        assertTrue(msg(() -> ExamValidator.paper(json(longQ))).contains("longer than"));
    }

    // ---------- results ----------

    @Test
    void marksAreNormalisedAndAbsentIsA() {
        ExamValidator.Result r = ExamValidator.result(json(
                "{\"total\":50,\"status\":\"draft\",\"marks\":{\"R1\":\"45.50\",\"R2\":\"ab\",\"R3\":\"\",\"R4\":30,\"R5\":null,\"R6\":\"0.0\",\"R7\":\"Abs\"}}"));
        assertEquals(50.0, r.total, 1e-9);
        assertEquals("45.5", r.marks.get("R1"));
        assertEquals("A", r.marks.get("R2"));
        assertFalse(r.marks.containsKey("R3"));
        assertEquals("30", r.marks.get("R4"));
        assertFalse(r.marks.containsKey("R5"));
        assertEquals("0", r.marks.get("R6"));
        assertEquals("A", r.marks.get("R7"));
    }

    @Test
    void marksAboveMaximumOrMalformedAreRejected() {
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"51\"}}"))).contains("between 0 and 50"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"-1\"}}"))).contains("between 0 and 50"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"abc\"}}"))).contains("number or A"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":true}}"))).contains("number or A"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"12.345\"}}"))).contains("2 decimal"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"marks\":{}}"))).contains("Maximum marks"));
        assertTrue(msg(() -> ExamValidator.result(json("{\"total\":50,\"status\":\"final\",\"marks\":{}}"))).contains("draft or published"));
    }

    @Test
    void hugeExponentsAreRejectedQuicklyInsteadOfHangingTheServer() throws Exception {
        long t0 = System.nanoTime();
        // default mapper: arrives as a Double (Infinity)
        assertThrows(ExamValidationException.class, () -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":1e999999999}}")));
        assertThrows(ExamValidationException.class, () -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"1e999999999\"}}")));
        // big-decimal mapper: must not expand 1e999999999 / 1e-999999999 into million-digit strings
        ObjectMapper big = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        assertThrows(ExamValidationException.class, () -> ExamValidator.result(big.readTree("{\"total\":50,\"marks\":{\"R1\":1e999999999}}")));
        assertThrows(ExamValidationException.class, () -> ExamValidator.result(big.readTree("{\"total\":50,\"marks\":{\"R1\":1e-999999999}}")));
        assertThrows(ExamValidationException.class, () -> ExamValidator.result(json("{\"total\":50,\"marks\":{\"R1\":\"1e-999999999\"}}")));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "validation of hostile numbers was too slow");
    }

    @Test
    void publishingNeedsAtLeastOneMark() {
        assertEquals("Enter at least one mark before publishing.",
                msg(() -> ExamValidator.result(json("{\"total\":50,\"status\":\"published\",\"marks\":{\"R1\":\"\"}}"))));
        assertEquals("published", ExamValidator.result(json("{\"total\":50,\"status\":\"published\",\"marks\":{\"R1\":\"A\"}}")).status);
    }

    // ---------- date sheet ----------

    @Test
    void dateSheetValidatesDateAndTimes() {
        ExamValidator.DateSheet d = ExamValidator.dateSheet(json(
                "{\"term\":\"Mid Term\",\"cls\":\"Class 5\",\"subject\":\"English\",\"date\":\"2026-10-20\",\"start\":\"09:00\",\"end\":\"11:00\",\"room\":\"Hall 1\"}"));
        assertEquals("09:00", d.start);
        assertEquals("Hall 1", d.room);
        String base = "\"term\":\"T\",\"cls\":\"C\",\"subject\":\"S\",\"date\":\"2026-10-20\"";
        assertTrue(msg(() -> ExamValidator.dateSheet(json("{" + base + ",\"start\":\"11:00\",\"end\":\"09:00\"}"))).contains("after the start"));
        assertTrue(msg(() -> ExamValidator.dateSheet(json("{" + base + ",\"start\":\"9:00\",\"end\":\"11:00\"}"))).contains("09:00"));
        assertTrue(msg(() -> ExamValidator.dateSheet(json("{" + base + ",\"start\":\"25:00\",\"end\":\"26:00\"}"))).contains("09:00"));
        assertTrue(msg(() -> ExamValidator.dateSheet(json("{\"term\":\"T\",\"cls\":\"C\",\"subject\":\"S\",\"start\":\"09:00\",\"end\":\"10:00\"}"))).contains("date"));
    }

    // ---------- grading ----------

    @Test
    void gradingIsValidatedAndSortedHighestFirst() throws Exception {
        String scale = ExamValidator.gradingScale(json(
                "{\"scale\":[{\"min\":0,\"grade\":\"F\",\"remark\":\"Fail\",\"pass\":false},{\"min\":50,\"grade\":\"B\",\"remark\":\"\",\"pass\":true},{\"min\":80,\"grade\":\"A\",\"pass\":true}]}"));
        JsonNode n = om.readTree(scale);
        assertEquals("A", n.get(0).get("grade").asText());
        assertEquals(80, n.get(0).get("min").asInt());
        assertEquals("F", n.get(2).get("grade").asText());
        assertFalse(n.get(2).get("pass").asBoolean());
    }

    @Test
    void gradingRulesMirrorTheScreen() {
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[]}"))).contains("at least one grade"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":50,\"grade\":\"A\",\"pass\":true}]}"))).contains("start at 0%"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":0,\"grade\":\"F\",\"pass\":false}]}"))).contains("at least one grade as Pass"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":0,\"grade\":\"A\",\"pass\":true},{\"min\":50,\"grade\":\"a\",\"pass\":true}]}"))).contains("used twice"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":0,\"grade\":\"A\",\"pass\":true},{\"min\":0,\"grade\":\"B\",\"pass\":true}]}"))).contains("Two grades start at 0%"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":0,\"grade\":\"A\",\"pass\":true},{\"min\":101,\"grade\":\"B\",\"pass\":true}]}"))).contains("between 0 and 100"));
        assertTrue(msg(() -> ExamValidator.gradingScale(json("{\"scale\":[{\"min\":0,\"grade\":\"\",\"pass\":true}]}"))).contains("Grade name"));
    }
}
