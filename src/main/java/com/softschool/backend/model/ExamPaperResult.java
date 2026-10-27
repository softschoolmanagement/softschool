package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Marks for one paper (Examination -> Result Upload). One row per paper.
 *
 *   status "draft"      -> editable, not used by Report Cards / Analysis
 *   status "published"  -> final; must be unpublished before it can change
 *
 * {@code marksJson} is a JSON object {studentRegNo: "45" | "45.5" | "A"}.
 * "A" means absent. Blank marks are simply not stored.
 */
@Entity
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "exam_paper_results",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_paper_result_school_paper", columnNames = {"school_id", "paper_id"}))
public class ExamPaperResult {

    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, length = 100)
    private String schoolId;

    @Column(name = "paper_id", nullable = false, length = 40)
    private String paperId;

    // Maximum marks the paper was marked out of.
    @Column(name = "total_marks", nullable = false)
    private Double totalMarks;

    @Column(name = "status", nullable = false, length = 12)
    private String status = DRAFT;

    @Column(name = "marks_json", nullable = false, columnDefinition = "LONGTEXT")
    private String marksJson = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
