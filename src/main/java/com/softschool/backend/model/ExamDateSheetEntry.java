package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** One line of the exam date sheet (Examination -> Date Sheet). */
@Entity
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "exam_datesheet",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_ds_school_eid", columnNames = {"school_id", "entry_id"}),
       indexes = @Index(name = "idx_exam_ds_school_date", columnList = "school_id,exam_date"))
public class ExamDateSheetEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, length = 100)
    private String schoolId;

    @Column(name = "entry_id", nullable = false, length = 40)
    private String entryId;

    @Column(name = "term", nullable = false, length = 40)
    private String term;

    @Column(name = "class_name", nullable = false, length = 80)
    private String className;

    @Column(name = "subject", nullable = false, length = 80)
    private String subject;

    @Column(name = "exam_date", nullable = false, length = 10)
    private String examDate;

    // "HH:mm" (24h), exactly as the time picker produces it.
    @Column(name = "start_time", nullable = false, length = 5)
    private String startTime;

    @Column(name = "end_time", nullable = false, length = 5)
    private String endTime;

    @Column(name = "room", length = 60)
    private String room;

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
