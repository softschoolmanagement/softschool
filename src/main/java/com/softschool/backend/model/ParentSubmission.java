package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Everything a parent sends from the portal that is not a chat message:
 *   kind "feedback"   -> Submit Feedback page (about the software)
 *   kind "leave"      -> leave / application
 *   kind "complaint"  -> complaint
 *   kind "suggestion" -> suggestion
 * imagePaths: one relative path per line (files live on disk via FileStorageService).
 */
@Entity
@Data
@Table(name = "parent_submission",
       indexes = {
           @Index(name = "idx_psub_school_created", columnList = "schoolId,createdAt"),
           @Index(name = "idx_psub_parent", columnList = "schoolId,phone,createdAt")
       })
public class ParentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 10)
    private String phone;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(length = 60)
    private String category;

    @Column(length = 200)
    private String title;

    @Column(length = 2000)
    private String body;

    @Column(length = 10)
    private String fromDate;

    @Column(length = 10)
    private String toDate;

    /** write | image | camera (how a leave application was composed). */
    @Column(length = 20)
    private String method;

    @Column(length = 64)
    private String studentRegNo;

    @Column(length = 120)
    private String studentName;

    @Column(length = 100)
    private String className;

    @Column(length = 120)
    private String guardianName;

    @Column(length = 1000)
    private String imagePaths;

    /** new | seen | resolved */
    @Column(nullable = false, length = 20)
    private String status = "new";

    @Column(length = 500)
    private String adminNote;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
