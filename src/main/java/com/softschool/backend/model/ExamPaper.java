package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A saved question paper (Examination -> Paper Generation).
 *
 * The question sections (types, marks, questions, MCQ options) are stored as
 * one validated JSON document in {@code sectionsJson}; nothing ever queries
 * inside it, so a column per question would only add joins.
 *
 * {@code paperId} is the id the browser uses (e.g. "xlq3k9a1b2"), unique per
 * school. Every query is scoped by schoolId, like Student/Staff/Finance.
 */
@Entity
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "exam_papers",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_paper_school_pid", columnNames = {"school_id", "paper_id"}),
       indexes = @Index(name = "idx_exam_paper_school_class", columnList = "school_id,class_name"))
public class ExamPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, length = 100)
    private String schoolId;

    @Column(name = "paper_id", nullable = false, length = 40)
    private String paperId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "term", nullable = false, length = 40)
    private String term;

    @Column(name = "class_name", nullable = false, length = 80)
    private String className;

    @Column(name = "section_name", length = 40)
    private String sectionName;

    @Column(name = "subject", nullable = false, length = 80)
    private String subject;

    // ISO date (yyyy-MM-dd) exactly as the date picker produces it; may be empty.
    @Column(name = "exam_date", length = 10)
    private String examDate;

    @Column(name = "duration_mins", nullable = false)
    private Integer durationMins = 90;

    @Column(name = "instructions", length = 2000)
    private String instructions;

    // Recomputed by the server from the sections, never trusted from the client.
    @Column(name = "total_marks", nullable = false)
    private Double totalMarks = 0d;

    @Column(name = "sections_json", nullable = false, columnDefinition = "LONGTEXT")
    private String sectionsJson;

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
