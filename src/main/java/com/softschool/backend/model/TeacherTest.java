package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A quiz/test created by a teacher in the Teacher Portal.
 *
 * The portal owns the shape of a test (questions, options, marks and every
 * student's checked result), so the whole object is kept as JSON in
 * payloadJson. Only the fields needed to find/list a test are real columns.
 * testId is the id the portal generated, unique per (school, teacher).
 */
@Entity
@Data
@Table(name = "teacher_test",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "staffId", "testId"}),
       indexes = @Index(name = "idx_tt_school_staff", columnList = "schoolId,staffId"))
public class TeacherTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false)
    private String staffId;

    @Column(nullable = false, length = 40)
    private String testId;

    private String title;
    private String className;
    private String subject;

    @Column(length = 10)
    private String type; // "quiz" | "test"

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
