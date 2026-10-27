package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * The school's custom grading scheme (Examination -> Grading Scheme).
 * One row per school; no row means "use the built-in default scale".
 * {@code scaleJson} is an array of {min, grade, remark, pass}, highest first.
 */
@Entity
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "exam_grading",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_grading_school", columnNames = {"school_id"}))
public class ExamGrading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, length = 100)
    private String schoolId;

    @Column(name = "scale_json", nullable = false, length = 4000)
    private String scaleJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
