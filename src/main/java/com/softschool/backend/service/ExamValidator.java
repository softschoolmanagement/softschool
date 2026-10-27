package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.softschool.backend.exception.ExamValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates and normalises every Examination payload.
 *
 * Kept free of Spring/JPA so the rules are plain Java that can be unit
 * tested on their own. Every method either returns clean, size-limited data
 * or throws {@link ExamValidationException} with a message that is safe to
 * show to the user. The rules deliberately mirror examination.js so a request
 * the screen accepts is never refused here, while anything crafted by hand
 * (wrong types, absurd sizes, marks above the maximum) is.
 */
public final class ExamValidator {

    private ExamValidator() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern ABSENT = Pattern.compile("^a(b|bs|bsent)?$", Pattern.CASE_INSENSITIVE);

    static final int MAX_SECTIONS = 20;
    static final int MAX_QUESTIONS_PER_SECTION = 300;
    static final int MAX_OPTIONS = 10;
    static final int MAX_QUESTION_CHARS = 5000;
    static final int MAX_OPTION_CHARS = 1000;
    static final int MAX_SECTIONS_JSON_CHARS = 600_000;
    static final int MAX_MARK_ENTRIES = 5000;
    static final int MAX_GRADES = 20;
    public static final String ABSENT_MARK = "A";

    // ---------- ids ----------

    /** Returns the id when it is safe to use as a key, otherwise throws. */
    public static String id(String raw, String what) {
        if (raw == null || !ID.matcher(raw).matches()) {
            throw bad("The " + what + " id is invalid (use letters, digits, - or _, up to 40 characters).");
        }
        return raw;
    }

    // ---------- paper ----------

    public static final class Paper {
        public String title, term, className, section, subject, date, instructions, sectionsJson;
        public int minutes;
        public double total;
    }

    public static Paper paper(JsonNode b) {
        requireObject(b);
        Paper p = new Paper();
        p.term = req(b, "term", 40, "Exam / Term");
        p.className = req(b, "cls", 80, "Class");
        p.section = opt(b, "section", 40);
        p.subject = req(b, "subj", 80, "Subject");
        p.date = optDate(b, "date", "Paper date");
        p.minutes = (int) Math.round(num(b, "mins", 90, 1, 1000, "Time (minutes)"));
        p.instructions = opt(b, "instr", 2000);
        String title = opt(b, "title", 200);
        p.title = title.isEmpty()
                ? p.subject + " \u2014 " + p.term + " (" + p.className + (p.section.isEmpty() ? "" : " (" + p.section + ")") + ")"
                : title;

        JsonNode secs = b.get("sections");
        if (secs == null || !secs.isArray()) throw bad("The paper has no sections.");
        if (secs.size() > MAX_SECTIONS) throw bad("A paper can have at most " + MAX_SECTIONS + " sections.");

        ArrayNode outSecs = MAPPER.createArrayNode();
        double total = 0;
        int filledQuestions = 0;
        for (JsonNode s : secs) {
            if (!s.isObject()) throw bad("Invalid section.");
            String type = req(s, "type", 40, "Section type");
            double attempt = num(s, "attempt", 0, 0, MAX_QUESTIONS_PER_SECTION, "Attempt count");
            double marks = num(s, "marks", 0, 0, 1000, "Marks per question");

            JsonNode qs = s.get("questions");
            if (qs == null || !qs.isArray()) throw bad("Section \"" + type + "\" has no questions list.");
            if (qs.size() > MAX_QUESTIONS_PER_SECTION) {
                throw bad("A section can have at most " + MAX_QUESTIONS_PER_SECTION + " questions.");
            }
            ArrayNode outQs = MAPPER.createArrayNode();
            int filled = 0;
            for (JsonNode q : qs) {
                if (!q.isObject()) throw bad("Invalid question.");
                String text = str(q.get("text"));
                if (text.length() > MAX_QUESTION_CHARS) {
                    throw bad("A question is longer than " + MAX_QUESTION_CHARS + " characters.");
                }
                ArrayNode outOpts = MAPPER.createArrayNode();
                JsonNode opts = q.get("opts");
                if (opts != null && !opts.isNull()) {
                    if (!opts.isArray() || opts.size() > MAX_OPTIONS) {
                        throw bad("A question can have at most " + MAX_OPTIONS + " options.");
                    }
                    for (JsonNode o : opts) {
                        String ot = str(o);
                        if (ot.length() > MAX_OPTION_CHARS) {
                            throw bad("An answer option is longer than " + MAX_OPTION_CHARS + " characters.");
                        }
                        outOpts.add(ot);
                    }
                }
                ObjectNode oq = MAPPER.createObjectNode();
                oq.put("text", text);
                oq.set("opts", outOpts);
                outQs.add(oq);
                if (!text.trim().isEmpty()) filled++;
            }
            // Same rule as the screen: "attempt any N" only counts when 0 < N < number of questions.
            double effective = attempt > 0 && attempt < filled ? attempt : filled;
            total += effective * marks;
            filledQuestions += filled;

            ObjectNode os = MAPPER.createObjectNode();
            os.put("type", type);
            os.put("attempt", (long) attempt);
            putNum(os, "marks", marks);
            os.set("questions", outQs);
            outSecs.add(os);
        }
        if (filledQuestions == 0) throw bad("Write at least one question.");

        p.total = round2(total);
        p.sectionsJson = outSecs.toString();
        if (p.sectionsJson.length() > MAX_SECTIONS_JSON_CHARS) throw bad("The paper is too large to save.");
        return p;
    }

    // ---------- results ----------

    public static final class Result {
        public double total;
        public String status;
        public Map<String, String> marks = new LinkedHashMap<>();
        public String marksJson;
    }

    public static Result result(JsonNode b) {
        requireObject(b);
        Result r = new Result();
        r.total = num(b, "total", Double.NaN, 0.01, 100000, "Maximum marks");
        if (Double.isNaN(r.total)) throw bad("Maximum marks is required.");
        r.total = round2(r.total);

        String status = opt(b, "status", 12).toLowerCase();
        if (status.isEmpty()) status = "draft";
        if (!status.equals("draft") && !status.equals("published")) throw bad("Status must be draft or published.");
        r.status = status;

        JsonNode marks = b.get("marks");
        if (marks != null && !marks.isNull()) {
            if (!marks.isObject()) throw bad("Marks must be an object of student -> marks.");
            if (marks.size() > MAX_MARK_ENTRIES) throw bad("Too many marks in one request.");
            Iterator<Map.Entry<String, JsonNode>> it = marks.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String reg = e.getKey() == null ? "" : e.getKey().trim();
                if (reg.isEmpty() || reg.length() > 60) throw bad("A student reference is invalid.");
                String m = normaliseMark(e.getValue(), reg, r.total);
                if (m != null) r.marks.put(reg, m);
            }
        }
        if (r.status.equals("published") && r.marks.isEmpty()) {
            throw bad("Enter at least one mark before publishing.");
        }
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, String> e : r.marks.entrySet()) out.put(e.getKey(), e.getValue());
        r.marksJson = out.toString();
        return r;
    }

    /** Returns null for a blank mark, "A" for absent, otherwise a clean decimal string ("45", "45.5"). */
    static String normaliseMark(JsonNode v, String reg, double total) {
        if (v == null || v.isNull()) return null;
        BigDecimal d;
        if (v.isNumber()) {
            // 1e999999999 arrives as a Double infinity; refuse it here instead of letting decimalValue() throw.
            if ((v.isDouble() || v.isFloat()) && (Double.isNaN(v.doubleValue()) || Double.isInfinite(v.doubleValue()))) {
                throw badMark(reg);
            }
            d = v.decimalValue();
        } else if (v.isTextual()) {
            String raw = v.asText().trim();
            if (raw.isEmpty()) return null;
            if (ABSENT.matcher(raw).matches()) return ABSENT_MARK;
            if (raw.length() > 24) throw badMark(reg);
            try {
                d = new BigDecimal(raw);
            } catch (NumberFormatException ex) {
                throw badMark(reg);
            }
        } else {
            throw badMark(reg);
        }
        // Range check first: it is cheap even for absurd exponents like 1e999999999.
        if (d.signum() < 0 || d.doubleValue() > total + 1e-9) {
            throw bad("Marks for " + reg + " must be between 0 and " + clean(total) + ".");
        }
        d = d.stripTrailingZeros();
        if (d.scale() > 2) throw bad("Marks for " + reg + " can have at most 2 decimal places.");
        return d.signum() == 0 ? "0" : d.toPlainString();
    }

    private static ExamValidationException badMark(String reg) {
        return bad("Marks for " + reg + " must be a number or A (absent).");
    }

    // ---------- date sheet ----------

    public static final class DateSheet {
        public String term, className, subject, date, start, end, room;
    }

    public static DateSheet dateSheet(JsonNode b) {
        requireObject(b);
        DateSheet d = new DateSheet();
        d.term = req(b, "term", 40, "Term");
        d.className = req(b, "cls", 80, "Class");
        d.subject = req(b, "subject", 80, "Subject");
        d.date = optDate(b, "date", "Date");
        if (d.date.isEmpty()) throw bad("Pick a date.");
        d.start = opt(b, "start", 5);
        d.end = opt(b, "end", 5);
        if (!TIME.matcher(d.start).matches() || !TIME.matcher(d.end).matches()) {
            throw bad("Start and end times must look like 09:00.");
        }
        if (d.end.compareTo(d.start) <= 0) throw bad("End time must be after the start time.");
        d.room = opt(b, "room", 60);
        return d;
    }

    // ---------- grading ----------

    /** Validates a grading scheme and returns the normalised scale as JSON (highest grade first). */
    public static String gradingScale(JsonNode b) {
        requireObject(b);
        JsonNode scale = b.get("scale");
        if (scale == null || !scale.isArray() || scale.size() == 0) throw bad("Add at least one grade.");
        if (scale.size() > MAX_GRADES) throw bad("A grading scheme can have at most " + MAX_GRADES + " grades.");

        List<ObjectNode> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<BigDecimal> mins = new HashSet<>();
        boolean anyPass = false, hasZero = false;
        for (JsonNode g : scale) {
            if (!g.isObject()) throw bad("Invalid grade row.");
            String name = req(g, "grade", 10, "Grade name");
            double min = num(g, "min", Double.NaN, 0, 100, "Grade \"" + name + "\": 'From %'");
            if (Double.isNaN(min)) throw bad("Grade " + name + ": 'From %' must be a number between 0 and 100.");
            if (!names.add(name.toLowerCase())) throw bad("The grade " + name + " is used twice.");
            BigDecimal key = BigDecimal.valueOf(min).stripTrailingZeros();
            if (!mins.add(key)) {
                throw bad("Two grades start at " + clean(min) + "%. Each grade needs its own starting percentage.");
            }
            JsonNode passNode = g.get("pass");
            boolean pass = passNode == null || passNode.isNull() || (passNode.isBoolean() ? passNode.asBoolean() : !"false".equalsIgnoreCase(passNode.asText()));
            if (pass) anyPass = true;
            if (min == 0) hasZero = true;

            ObjectNode row = MAPPER.createObjectNode();
            putNum(row, "min", min);
            row.put("grade", name);
            row.put("remark", opt(g, "remark", 60));
            row.put("pass", pass);
            rows.add(row);
        }
        if (!hasZero) throw bad("One grade must start at 0% so every mark gets a grade (normally the fail grade).");
        if (!anyPass) throw bad("Mark at least one grade as Pass.");
        rows.sort(Comparator.comparingDouble((ObjectNode r) -> r.get("min").asDouble()).reversed());
        ArrayNode out = MAPPER.createArrayNode();
        rows.forEach(out::add);
        return out.toString();
    }

    // ---------- helpers ----------

    private static void requireObject(JsonNode b) {
        if (b == null || !b.isObject()) throw bad("A JSON object is required.");
    }

    private static String str(JsonNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isContainerNode()) throw bad("Unexpected nested value.");
        return n.asText("");
    }

    private static String opt(JsonNode b, String field, int max) {
        String v = str(b.get(field)).trim();
        if (v.length() > max) throw bad("\"" + field + "\" is too long (maximum " + max + " characters).");
        return v;
    }

    private static String req(JsonNode b, String field, int max, String label) {
        String v = opt(b, field, max);
        if (v.isEmpty()) throw bad(label + " is required.");
        return v;
    }

    private static String optDate(JsonNode b, String field, String label) {
        String v = opt(b, field, 10);
        if (v.isEmpty()) return "";
        try {
            LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            throw bad(label + " must be a valid date (yyyy-mm-dd).");
        }
        return v;
    }

    /** Reads a number that may arrive as a JSON number or a numeric string; absent -> fallback. */
    private static double num(JsonNode b, String field, double fallback, double min, double max, String label) {
        JsonNode n = b.get(field);
        if (n == null || n.isNull() || (n.isTextual() && n.asText().trim().isEmpty())) return fallback;
        double v;
        if (n.isNumber()) {
            v = n.asDouble();
        } else if (n.isTextual()) {
            try {
                v = Double.parseDouble(n.asText().trim());
            } catch (NumberFormatException ex) {
                throw bad(label + " must be a number.");
            }
        } else {
            throw bad(label + " must be a number.");
        }
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            throw bad(label + " must be between " + clean(min) + " and " + clean(max) + ".");
        }
        return v;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 5.0 -> 5 (a long), 2.5 -> 2.5, so JSON stays tidy. Used for messages. */
    static Number clean(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    private static void putNum(ObjectNode node, String field, double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) node.put(field, (long) v);
        else node.put(field, v);
    }

    private static ExamValidationException bad(String m) {
        return new ExamValidationException(m);
    }
}
