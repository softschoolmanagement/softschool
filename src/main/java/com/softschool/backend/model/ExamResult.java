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
@EntityListeners(com.softschool.backend.service.DataCacheInvalidationListener.class)
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

    /**
     * Parents see a result ONLY when the admin has sent it (visible = true).
     * A row with visible = false is a result the admin is holding back (or has withdrawn): it is kept so
     * "send to whole class" skips that student, but it is never returned by the parent API.
     * The column default keeps rows that existed before this field was added visible.
     */
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean visible = true;
}
