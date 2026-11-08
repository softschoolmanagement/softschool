package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Feedback about the software sent by a teacher from the teacher portal.
 *   type "bug"        -> something is broken / not working
 *   type "suggestion" -> an idea or improvement
 * Read by the Super Admin (Feedback page in superadmin.html) through FeedbackAdminController.
 */
@Entity
@Data
@Table(name = "teacher_feedback",
       indexes = {
           @Index(name = "idx_tfb_created", columnList = "createdAt"),
           @Index(name = "idx_tfb_school", columnList = "schoolId,createdAt"),
           @Index(name = "idx_tfb_teacher", columnList = "schoolId,staffId,createdAt")
       })
public class TeacherFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 64)
    private String staffId;

    @Column(length = 120)
    private String teacherName;

    /** bug | suggestion */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
