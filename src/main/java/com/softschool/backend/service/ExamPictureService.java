package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.softschool.backend.exception.ExamValidationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pictures and shapes of a question paper (Examination -> Paper Generation).
 *
 * Pictures are NOT kept in the database. Each one is a real file in a folder, one
 * sub-folder per school, and the paper only stores the short path:
 *
 *     uploads/exam-images/{school}/{uuid}.jpg|png      on disk
 *     "exam-images/{uuid}.jpg"                         what the paper stores / the browser sees
 *
 * The school folder is always taken from the verified session, never from the path the
 * browser sends, so one school can never read or reference another school's files.
 *
 * What goes into {@code ExamPaper.layoutJson} (small: paths and numbers only):
 *   {"v":1,
 *    "qimgs":[{"s":0,"q":2,"p":"exam-images/....jpg"}],     picture printed under a question
 *    "items":[{"id":..,"type":"image|rect|ellipse|triangle|hline|vline","x":..,"y":..,"w":..,"h":..,
 *              "fill":..,"stroke":..,"sw":..,"op":..,"behind":..,"ar":..,"src":"exam-images/....png"}]}
 *
 * Questions and their pictures are matched by the order of the NON-BLANK questions, so the
 * result is the same whether or not ExamValidator drops blank questions.
 *
 * Everything the browser sends is rebuilt field by field here (whitelist), never stored as is.
 */
@Service
public class ExamPictureService {

    private static final Logger log = LoggerFactory.getLogger(ExamPictureService.class);

    public static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_ITEMS = 40;
    private static final int MAX_QUESTION_PICTURES = 100;
    private static final int MAX_FILES_PER_SCHOOL = 5000;
    private static final String PREFIX = "exam-images/";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern PATH = Pattern.compile("^exam-images/(" + UUID_RE + ")\\.(jpg|png)$");
    private static final Pattern NAME = Pattern.compile("^" + UUID_RE + "\\.(jpg|png)$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{3,8}$");
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList("image", "rect", "ellipse", "triangle", "hline", "vline"));

    /** Folder that holds every school's pictures. Point it at the same persistent disk as "uploads/". */
    @Value("${app.exam-images.dir:uploads/exam-images}")
    private String dir;

    private Path base;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void init() {
        base = Paths.get(dir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            log.warn("Could not create the exam pictures folder {}: {}", base, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ result types

    public record Loaded(byte[] bytes, String mediaType) { }

    /** What was found in a paper body before validation. */
    public static final class Extracted {
        private final List<Pic> pics = new ArrayList<>();
        private int filled;
        private JsonNode items;
    }

    private static final class Pic {
        final int flat;
        final String value;
        Pic(int flat, String value) { this.flat = flat; this.value = value; }
    }

    /** The layout to store, the paths it uses and the files this call newly wrote. */
    public static final class Layout {
        public final String json;              // null when the paper has no pictures or shapes
        public final Set<String> names;        // every "exam-images/..." path it references
        public final List<String> created;     // files written by this call (delete them if the save fails)
        Layout(String json, Set<String> names, List<String> created) { this.json = json; this.names = names; this.created = created; }
    }

    // ------------------------------------------------------------------ paper body -> layout

    /**
     * Takes the pictures and shapes OUT of the body, so ExamValidator only ever sees the
     * same questions-and-marks document it always did. {@code body} must be a private copy.
     */
    public Extracted extract(ObjectNode body) {
        Extracted ex = new Extracted();
        int flat = 0;
        JsonNode secs = body.path("sections");
        if (secs.isArray()) {
            for (JsonNode s : secs) {
                JsonNode qs = s.path("questions");
                if (!qs.isArray()) continue;
                for (JsonNode q : qs) {
                    if (!q.isObject()) continue;
                    ObjectNode qo = (ObjectNode) q;
                    JsonNode img = qo.remove("img");
                    if (qo.path("text").asText("").trim().isEmpty()) continue;
                    if (img != null && img.isTextual() && !img.asText().trim().isEmpty()) ex.pics.add(new Pic(flat, img.asText()));
                    flat++;
                }
            }
        }
        ex.filled = flat;
        ex.items = body.remove("items");
        return ex;
    }

    /**
     * Turns the extracted pictures into files + the layout JSON. Call it AFTER ExamValidator
     * accepted the paper, with the validated sections, so nothing is written for a bad paper.
     */
    public Layout build(String schoolId, Extracted ex, String validatedSectionsJson) {
        List<String> created = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        try {
            List<int[]> coords = new ArrayList<>();     // [section, question] of every non-blank question, in order
            JsonNode secs = parse(validatedSectionsJson);
            if (secs.isArray()) {
                for (int s = 0; s < secs.size(); s++) {
                    JsonNode qs = secs.get(s).path("questions");
                    if (!qs.isArray()) continue;
                    for (int q = 0; q < qs.size(); q++) {
                        if (!qs.get(q).path("text").asText("").trim().isEmpty()) coords.add(new int[]{s, q});
                    }
                }
            }
            if (!ex.pics.isEmpty() && coords.size() != ex.filled) {
                throw new ExamValidationException("The pictures could not be matched to the questions. Remove the question pictures and add them again.");
            }
            if (ex.pics.size() > MAX_QUESTION_PICTURES) {
                throw new ExamValidationException("A paper can have at most " + MAX_QUESTION_PICTURES + " question pictures.");
            }

            ObjectNode root = mapper.createObjectNode();
            root.put("v", 1);
            ArrayNode qa = root.putArray("qimgs");
            for (Pic pic : ex.pics) {
                String path = resolve(schoolId, pic.value, created);
                if (path == null) continue;
                int[] c = coords.get(pic.flat);
                ObjectNode o = qa.addObject();
                o.put("s", c[0]);
                o.put("q", c[1]);
                o.put("p", path);
                names.add(path);
            }
            ArrayNode ia = root.putArray("items");
            if (ex.items != null && ex.items.isArray()) {
                for (JsonNode it : ex.items) {
                    if (ia.size() >= MAX_ITEMS) break;
                    ObjectNode o = cleanItem(schoolId, it, created, names);
                    if (o != null) ia.add(o);
                }
            }
            String json = qa.size() == 0 && ia.size() == 0 ? null : root.toString();
            return new Layout(json, names, created);
        } catch (RuntimeException e) {
            for (String p : created) deleteQuietly(schoolId, p);
            throw e;
        }
    }

    private ObjectNode cleanItem(String schoolId, JsonNode it, List<String> created, Set<String> names) {
        if (it == null || !it.isObject()) return null;
        String type = it.path("type").asText("");
        if (!TYPES.contains(type)) return null;
        ObjectNode o = mapper.createObjectNode();
        String id = it.path("id").asText("");
        if (!ID.matcher(id).matches()) id = "x" + Long.toString(System.nanoTime(), 36);
        o.put("id", id);
        o.put("type", type);
        o.put("x", num(it.path("x"), 0, 1000, 0));
        o.put("y", num(it.path("y"), 0, 30000, 0));
        o.put("w", num(it.path("w"), 1, 3000, 100));
        o.put("h", num(it.path("h"), 1, 30000, 100));
        o.put("fill", color(it.path("fill").asText(""), ""));
        o.put("stroke", color(it.path("stroke").asText(""), "#111111"));
        o.put("sw", num(it.path("sw"), 0, 12, 2));
        o.put("op", num(it.path("op"), 0.05, 1, 1));
        o.put("behind", it.path("behind").asBoolean(false));
        if ("image".equals(type)) {
            String path = resolve(schoolId, it.path("src").asText(""), created);
            if (path == null) return null;           // a picture that cannot be found is dropped, not stored as a dead link
            o.put("src", path);
            o.put("ar", num(it.path("ar"), 0.01, 100, 1));
            names.add(path);
        }
        return o;
    }

    private static double num(JsonNode n, double min, double max, double def) {
        if (n == null || !n.isNumber()) return def;
        double v = n.asDouble();
        if (Double.isNaN(v) || Double.isInfinite(v)) return def;
        return Math.round(Math.min(max, Math.max(min, v)) * 100d) / 100d;
    }

    private static String color(String c, String def) {
        return c != null && COLOR.matcher(c).matches() ? c : def;
    }

    /** data URL -> new file; an existing "exam-images/..." path of this school -> itself; anything else -> null. */
    private String resolve(String schoolId, String value, List<String> created) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("data:")) {
            String path = storeBytes(schoolId, decodeDataUrl(v));
            created.add(path);
            return path;
        }
        return exists(schoolId, v) ? v : null;
    }

    // ------------------------------------------------------------------ layout -> what the browser gets

    /** Puts each question's picture path back into the sections the browser expects. */
    public JsonNode attach(JsonNode sections, String layoutJson) {
        JsonNode qa = parse(layoutJson).path("qimgs");
        if (!qa.isArray() || sections == null || !sections.isArray()) return sections;
        for (JsonNode e : qa) {
            int s = e.path("s").asInt(-1), q = e.path("q").asInt(-1);
            String p = e.path("p").asText("");
            if (s < 0 || q < 0 || p.isEmpty()) continue;
            JsonNode sec = sections.get(s);
            JsonNode qn = sec == null ? null : sec.path("questions").get(q);
            if (qn instanceof ObjectNode) ((ObjectNode) qn).put("img", p);
        }
        return sections;
    }

    public JsonNode items(String layoutJson) {
        JsonNode items = parse(layoutJson).path("items");
        return items.isArray() ? items : mapper.createArrayNode();
    }

    /** Every picture path a stored layout refers to. */
    public Set<String> namesIn(String layoutJson) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode root = parse(layoutJson);
        for (JsonNode e : root.path("qimgs")) addPath(out, e.path("p").asText(""));
        for (JsonNode it : root.path("items")) addPath(out, it.path("src").asText(""));
        return out;
    }

    private static void addPath(Set<String> out, String p) {
        if (PATH.matcher(p).matches()) out.add(p);
    }

    private JsonNode parse(String json) {
        if (json == null || json.trim().isEmpty()) return mapper.createObjectNode();
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    // ------------------------------------------------------------------ files

    public static boolean isPath(String v) {
        return v != null && PATH.matcher(v).matches();
    }

    private Path schoolDir(String schoolId) {
        String safe = schoolId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        return base.resolve(safe + "-" + Integer.toHexString(schoolId.hashCode()));
    }

    private Path fileOf(String schoolId, String path) {
        Matcher m = PATH.matcher(path == null ? "" : path);
        if (!m.matches()) return null;
        Path dir = schoolDir(schoolId), f = dir.resolve(m.group(1) + "." + m.group(2)).normalize();
        return f.startsWith(dir) ? f : null;
    }

    public boolean exists(String schoolId, String path) {
        Path f = fileOf(schoolId, path);
        return f != null && Files.isRegularFile(f);
    }

    /** Validates the bytes (real JPEG/PNG, sane size) and writes them as a new file. Returns "exam-images/{uuid}.ext". */
    public String storeBytes(String schoolId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new ExamValidationException("The picture is empty.");
        if (bytes.length > MAX_IMAGE_BYTES) throw new ExamValidationException("Each picture must be smaller than 3 MB.");
        String ext = detect(bytes);
        if (ext == null) throw new ExamValidationException("Only JPEG and PNG pictures are accepted.");
        checkDimensions(bytes);
        Path dir = schoolDir(schoolId);
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.list(dir)) {
                if (s.count() >= MAX_FILES_PER_SCHOOL) throw new ExamValidationException("This school has reached the limit of stored paper pictures.");
            }
            String name = UUID.randomUUID() + "." + ext;
            Path tmp = Files.createTempFile(dir, "up-", ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return PREFIX + name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The picture for a request like GET /api/exams/images/{name}; null when it is not this school's. */
    public Loaded read(String schoolId, String name) {
        if (name == null || !NAME.matcher(name).matches()) return null;
        Path f = fileOf(schoolId, PREFIX + name);
        if (f == null || !Files.isRegularFile(f)) return null;
        try {
            byte[] b = Files.readAllBytes(f);
            String ext = detect(b);
            return ext == null ? null : new Loaded(b, "jpg".equals(ext) ? "image/jpeg" : "image/png");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteQuietly(String schoolId, String path) {
        Path f = fileOf(schoolId, path);
        if (f == null) return;
        try {
            Files.deleteIfExists(f);
        } catch (IOException e) {
            log.warn("Could not delete exam picture {}: {}", f, e.getMessage());
        }
    }

    /** For when a school is permanently deleted (call it next to ExamPaperRepository.deleteBySchoolId). */
    public void deleteSchool(String schoolId) {
        Path dir = schoolDir(schoolId);
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException e) { log.warn("Could not delete {}: {}", p, e.getMessage()); }
            });
        } catch (IOException e) {
            log.warn("Could not clean the exam pictures of a deleted school: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ image checks

    private static byte[] decodeDataUrl(String v) {
        int comma = v.indexOf(',');
        String head = comma > 0 ? v.substring(0, comma).toLowerCase(Locale.ROOT) : "";
        if (!(head.startsWith("data:image/png;base64") || head.startsWith("data:image/jpeg;base64") || head.startsWith("data:image/jpg;base64"))) {
            throw new ExamValidationException("A picture is not in a supported format (JPEG or PNG).");
        }
        if (v.length() > MAX_IMAGE_BYTES / 3 * 4 + 200) throw new ExamValidationException("Each picture must be smaller than 3 MB.");
        try {
            return Base64.getMimeDecoder().decode(v.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new ExamValidationException("A picture could not be read.");
        }
    }

    /** Trusts the bytes, not the name or the declared type. */
    private static String detect(byte[] b) {
        if (b.length > 12 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length > 12 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "png";
        return null;
    }

    /** Reads only the header, so a "decompression bomb" is refused without decoding it. */
    private static void checkDimensions(byte[] b) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            Iterator<ImageReader> it = in == null ? Collections.<ImageReader>emptyIterator() : ImageIO.getImageReaders(in);
            if (!it.hasNext()) throw new ExamValidationException("That file is not a valid picture.");
            ImageReader r = it.next();
            try {
                r.setInput(in);
                int w = r.getWidth(0), h = r.getHeight(0);
                if (w < 1 || h < 1 || w > 6000 || h > 6000 || (long) w * h > 25_000_000L) throw new ExamValidationException("That picture is too large (maximum 6000 x 6000 pixels).");
            } finally {
                r.dispose();
            }
        } catch (IOException e) {
            throw new ExamValidationException("That file is not a valid picture.");
        }
    }
}
