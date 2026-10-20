package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One student's marks for one session + term. The backend had no exam-result data before the parent
 * portal, so this is a new table. payloadJson = {"subjects":[{"name":"Maths","total":100,"obtained":88},...]}.
 * session looks like "2025-2026", term like "first-term" (the keys result.js already uses).
 */
@Entity
@Data
@Table(name = "exam_result",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "regNo", "session", "term"}),
       indexes = @Index(name = "idx_examres_student", columnList = "schoolId,regNo"))
public class ExamResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 64)
    private String regNo;

    @Column(nullable = false, length = 20)
    private String session;

    @Column(nullable = false, length = 40)
    private String term;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
