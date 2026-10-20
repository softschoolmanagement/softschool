package com.softschool.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.model.ParentLinkedChild;
import com.softschool.backend.repository.ParentChildRepository;
import com.softschool.backend.repository.ParentLinkedChildRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who are this parent's children? A parent is just a phone number, and the children are every active
 * student whose phone1 or phone2 ends with those 10 digits. Computed on every request, so if the
 * school edits a phone number, links a sibling, or archives a student the parent's access follows
 * immediately.
 */
@Service
public class ParentChildrenService {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ParentChildRepository childRepository;

    @Autowired
    private ParentLinkedChildRepository linkedChildRepository;

    /** Children across ALL schools (login only — the school is not known yet). */
    public List<ParentChildDTO> childrenAcrossSchools(String tail10) {
        return verified(childRepository.findActiveByPhone("%" + tail10), tail10);
    }

    /**
     * Children inside one school (every request after login): the students that carry this phone number
     * UNION children attached at sign-up and siblings linked in the school's current student records.
     */
    public List<ParentChildDTO> childrenOf(String schoolId, String tail10) {
        List<ParentChildDTO> out = verified(childRepository.findActiveByPhoneInSchool(schoolId, "%" + tail10), tail10);
        Set<String> have = new LinkedHashSet<>();
        for (ParentChildDTO c : out) have.add(c.getRegNo());

        List<String> extra = new ArrayList<>();
        for (ParentLinkedChild l : linkedChildRepository.findBySchoolIdAndPhone(schoolId, tail10)) {
            if (!have.contains(l.getRegNo())) extra.add(l.getRegNo());
        }
        if (!extra.isEmpty()) {
            for (ParentChildDTO c : childRepository.findActiveByRegNosInSchool(schoolId, extra)) {
                if (have.add(c.getRegNo())) out.add(c);
            }
        }

        // ParentLinkedChild is a snapshot made at signup. A school may mark a student as a sibling
        // much later, so resolve the family metadata for the parent's already-authorized children
        // on every request instead of requiring another signup or a parent-account migration.
        if (!have.isEmpty()) {
            List<String> authorizedRegNos = new ArrayList<>(have);
            Set<String> relatedRegNos = new LinkedHashSet<>();

            for (String groupId : childRepository.findSiblingGroupIdsInSchool(schoolId, authorizedRegNos)) {
                if (groupId == null || groupId.isBlank()) continue;
                relatedRegNos.addAll(childRepository.findActiveRegNosBySiblingGroup(schoolId, groupId.trim()));
            }

            for (String regNo : authorizedRegNos) {
                for (String siblingsJson : childRepository.findHasSiblingsJson(schoolId, regNo)) {
                    relatedRegNos.addAll(siblingRegNos(siblingsJson));
                }
            }

            relatedRegNos.removeAll(have);
            if (!relatedRegNos.isEmpty()) {
                for (ParentChildDTO c : childRepository.findActiveByRegNosInSchool(schoolId, relatedRegNos)) {
                    if (have.add(c.getRegNo())) out.add(c);
                }
            }
        }
        return out;
    }

    /** Parse the school's stored [{name, regNo}, ...] sibling list; malformed data adds no access. */
    private Set<String> siblingRegNos(String siblingsJson) {
        Set<String> regNos = new LinkedHashSet<>();
        if (siblingsJson == null || siblingsJson.isBlank()) return regNos;
        try {
            JsonNode siblings = JSON.readTree(siblingsJson);
            if (!siblings.isArray()) return regNos;
            for (JsonNode sibling : siblings) {
                String regNo = sibling.path("regNo").asText("").trim();
                if (!regNo.isEmpty()) regNos.add(regNo);
            }
        } catch (Exception ignored) {
            // A malformed sibling list must not block the parent portal or grant extra access.
        }
        return regNos;
    }

    /** SQL narrowed the list; this re-checks it in Java so a formatting oddity can never add a stranger's child. */
    private List<ParentChildDTO> verified(List<ParentChildDTO> rows, String tail10) {
        List<ParentChildDTO> out = new ArrayList<>();
        for (ParentChildDTO c : rows) {
            if (ParentUtil.phoneMatches(c.getPhone1(), tail10) || ParentUtil.phoneMatches(c.getPhone2(), tail10)) out.add(c);
        }
        return out;
    }

    public static Map<String, List<ParentChildDTO>> bySchool(List<ParentChildDTO> all) {
        Map<String, List<ParentChildDTO>> m = new LinkedHashMap<>();
        for (ParentChildDTO c : all) m.computeIfAbsent(c.getSchoolId(), k -> new ArrayList<>()).add(c);
        return m;
    }

    /**
     * The JSON a parent screen needs for one child. Keys line up with what parent-portal-students.js
     * already reads (id, name, className, section, classSection, regNumber, rollNumber, guardianName,
     * primaryPhone, secondaryPhone, permanentAddress, photo).
     */
    public static Map<String, Object> view(ParentChildDTO c) {
        Map<String, Object> m = new LinkedHashMap<>();
        String cls = c.getStudentClass() == null ? "" : c.getStudentClass().trim();
        String sec = c.getSection() == null ? "" : c.getSection().trim();
        m.put("id", c.getRegNo());
        m.put("regNo", c.getRegNo());
        m.put("regNumber", c.getRegNo());
        m.put("name", c.getFullName());
        m.put("fullName", c.getFullName());
        m.put("rollNumber", c.getRollNo());
        m.put("className", cls);
        m.put("section", sec);
        m.put("classSection", sec.isEmpty() ? cls : cls + " – " + sec);
        m.put("classLabel", ParentUtil.classLabel(c));
        m.put("gender", c.getGender());
        m.put("guardianName", c.getGuardianName());
        m.put("guardianRole", c.getGuardianRole());
        m.put("fatherName", c.getFatherFullName());
        m.put("primaryPhone", c.getPhone1());
        m.put("secondaryPhone", c.getPhone2());
        m.put("permanentAddress", c.getPermanentAddress());
        m.put("hasPhoto", c.isHasPhoto());
        // Fetch with the Authorization header and show as a blob — an <img src> cannot send the token.
        m.put("photoUrl", c.isHasPhoto() ? "/api/parent/children/" + c.getRegNo() + "/photo" : null);
        return m;
    }
}
