package com.softschool.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Student;
import com.softschool.backend.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Automatically links students as siblings when their FATHER'S NAME and
 * FATHER'S CNIC both match (within the same school).
 *
 * Called by StudentController after an edit that changed either father field.
 * It writes the same four columns the manual "Mark as Sibling" flow in
 * manage-students.js writes, so the UI, the parent portal and the fee
 * screens treat auto-linked students exactly like manually linked ones:
 *
 *   siblingGroupId  shared family code, e.g. "004"
 *   isSibling       true
 *   siblingOf       display text, e.g. "Sibling of Ali and Ahmed"
 *   hasSiblings     JSON [{name, regNo}, ...] of the OTHER family members
 *
 * Only ACTIVE students are matched/linked (status null or "active"), so
 * "promoted" history rows (same child, new session) and dropped/graduated
 * students never create false sibling links.
 *
 * It does NOT touch fees: siblingDiscount is left exactly as the school set it.
 */
@Service
public class SiblingLinkService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CNIC_DIGITS = 13;

    @Autowired
    private StudentRepository studentRepository;

    /** CNIC reduced to digits only; null unless it is a full 13-digit CNIC. */
    public static String normalizeCnic(String cnic) {
        if (cnic == null) return null;
        String digits = cnic.replaceAll("\\D", "");
        return digits.length() == CNIC_DIGITS ? digits : null;
    }

    /** Case/space/punctuation-insensitive name; null if blank or a single character. */
    public static String normalizeName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return n.length() >= 2 ? n : null;
    }

    private static boolean isActive(Student s) {
        return s.getStatus() == null || "active".equalsIgnoreCase(s.getStatus().trim());
    }

    /**
     * Links the given student with every other active student of the same
     * school that has the same father name + father CNIC. Returns the
     * (possibly updated) student; if nothing matches, it is returned unchanged.
     */
    @Transactional
    public synchronized Student linkByFatherDetails(Long studentId) {
        Student me = studentRepository.findById(studentId).orElse(null);
        if (me == null || !isActive(me) || me.getSchoolId() == null) return me;

        String cnic = normalizeCnic(me.getFatherCnic());
        String name = normalizeName(me.getFatherFullName());
        if (cnic == null || name == null) return me;

        List<Student> matches = new ArrayList<>();
        for (Student s : studentRepository.findActiveByFatherCnicDigits(me.getSchoolId(), me.getId(), cnic)) {
            if (name.equals(normalizeName(s.getFatherFullName()))) matches.add(s);
        }
        if (matches.isEmpty()) return me;

        // Everyone who ends up in the family: me, the matches, and anyone
        // already sharing a group with either (so two half-built groups merge
        // into one instead of leaving the family split).
        Map<Long, Student> family = new LinkedHashMap<>();
        family.put(me.getId(), me);
        for (Student m : matches) family.put(m.getId(), m);

        Set<String> existingGroups = new LinkedHashSet<>();
        for (Student s : new ArrayList<>(family.values())) {
            if (s.getSiblingGroupId() != null && !s.getSiblingGroupId().trim().isEmpty()) {
                existingGroups.add(s.getSiblingGroupId().trim());
            }
        }
        for (String gid : existingGroups) {
            for (Student s : studentRepository.findBySchoolIdAndSiblingGroupId(me.getSchoolId(), gid)) {
                if (isActive(s)) family.putIfAbsent(s.getId(), s);
            }
        }
        if (family.size() < 2) return me;

        String groupId = pickGroupId(me.getSchoolId(), existingGroups);

        List<Student> toSave = new ArrayList<>();
        for (Student member : family.values()) {
            List<Student> others = new ArrayList<>();
            for (Student o : family.values()) if (!o.getId().equals(member.getId())) others.add(o);

            String siblingOf = "Sibling of " + joinNames(others);
            String hasSiblings = toJson(others);

            boolean changed = !groupId.equals(member.getSiblingGroupId())
                    || !Boolean.TRUE.equals(member.getIsSibling())
                    || !Objects.equals(siblingOf, member.getSiblingOf())
                    || !Objects.equals(hasSiblings, member.getHasSiblings());
            if (changed) {
                member.setSiblingGroupId(groupId);
                member.setIsSibling(true);
                member.setSiblingOf(siblingOf);
                member.setHasSiblings(hasSiblings);
                toSave.add(member);
            }
        }
        if (!toSave.isEmpty()) studentRepository.saveAll(toSave);
        return me;
    }

    /**
     * Called after an edit changed the student's father name / CNIC.
     *
     * If the student was in a sibling group (oldGroupId, read BEFORE the edit
     * was applied) and their father details no longer match ANY other active
     * member of that group, they are taken out of the family:
     *   - their own sibling columns are cleared (siblingGroupId, isSibling,
     *     siblingOf, hasSiblings);
     *   - the remaining members are rebuilt without them; if only one member
     *     is left, that member is cleared too (one person is not a family).
     *
     * If the new father details still match someone in the group, nothing
     * changes. Fees (siblingDiscount) are never touched. Call
     * linkByFatherDetails() afterwards so the student can join a different
     * family if the new father details match one.
     */
    @Transactional
    public synchronized Student unlinkIfFatherChanged(Long studentId, String oldGroupId) {
        Student me = studentRepository.findById(studentId).orElse(null);
        if (me == null || me.getSchoolId() == null || !isActive(me)) return me;
        String gid = oldGroupId == null ? "" : oldGroupId.trim();
        if (gid.isEmpty()) return me;

        List<Student> others = new ArrayList<>();
        for (Student s : studentRepository.findBySchoolIdAndSiblingGroupId(me.getSchoolId(), gid)) {
            if (!s.getId().equals(me.getId()) && isActive(s)) others.add(s);
        }

        String cnic = normalizeCnic(me.getFatherCnic());
        String name = normalizeName(me.getFatherFullName());
        if (cnic != null && name != null) {
            for (Student o : others) {
                if (cnic.equals(normalizeCnic(o.getFatherCnic()))
                        && name.equals(normalizeName(o.getFatherFullName()))) {
                    return me; // still the same father as someone in the family
                }
            }
        }

        List<Student> toSave = new ArrayList<>();
        clearSiblingFields(me);
        toSave.add(me);

        if (others.size() == 1) {
            Student last = others.get(0);
            clearSiblingFields(last);
            toSave.add(last);
        } else {
            for (Student member : others) {
                List<Student> rest = new ArrayList<>();
                for (Student o : others) if (!o.getId().equals(member.getId())) rest.add(o);
                member.setSiblingOf(joinNames(rest));
                member.setHasSiblings(toJson(rest));
                toSave.add(member);
            }
        }
        studentRepository.saveAll(toSave);
        return me;
    }

    private static void clearSiblingFields(Student s) {
        s.setSiblingGroupId(null);
        s.setIsSibling(false);
        s.setSiblingOf(null);
        s.setHasSiblings(null);
    }

    /** Lowest existing numeric group among the family, else the next free "00X" code for the school. */
    private String pickGroupId(String schoolId, Set<String> existingGroups) {
        Long lowest = null;
        String lowestRaw = null;
        for (String g : existingGroups) {
            Long n = numeric(g);
            if (n != null && (lowest == null || n < lowest)) { lowest = n; lowestRaw = g; }
        }
        if (lowestRaw != null) return lowestRaw;
        if (!existingGroups.isEmpty()) return existingGroups.iterator().next(); // non-numeric code: keep it

        long max = 0;
        for (String g : studentRepository.findSiblingGroupIdsBySchoolId(schoolId)) {
            Long n = numeric(g);
            if (n != null && n > max) max = n;
        }
        return String.format("%03d", max + 1);
    }

    private static Long numeric(String g) {
        if (g == null || !g.trim().matches("\\d{1,9}")) return null;
        return Long.parseLong(g.trim());
    }

    /** "Ali", "Ali and Ahmed", "Ali, Ahmed and Sara". */
    private static String joinNames(List<Student> others) {
        List<String> names = new ArrayList<>();
        for (Student o : others) names.add(o.getFullName() == null ? o.getRegNo() : o.getFullName());
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String toJson(List<Student> others) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Student o : others) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", o.getFullName());
            m.put("regNo", o.getRegNo());
            list.add(m);
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
