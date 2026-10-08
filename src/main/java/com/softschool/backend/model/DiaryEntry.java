package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A homework diary posted by a teacher: pictures of the diary page for one
 * class and subject.
 *
 * PARENT PORTAL (later): parents of className read these through the
 * class-level listing in DiaryController. Only rows with published = true
 * are meant for parents, so an admin can hide an entry without deleting it.
 *
 * imagePaths holds one relative path per line (e.g. "diary/3f1c....jpg"),
 * files live on disk via FileStorageService, never inside the database.
 */
@Entity
@Data
@Table(name = "diary_entry",
       indexes = {
           @Index(name = "idx_diary_staff", columnList = "schoolId,staffId,createdAt"),
           @Index(name = "idx_diary_class", columnList = "schoolId,className,createdAt")
       })
public class DiaryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 64)
    private String staffId;

    @Column(length = 120)
    private String staffName;

    /** Class label as the teacher sees it, e.g. "Class 5 - A". */
    @Column(nullable = false, length = 100)
    private String className;

    @Column(nullable = false, length = 80)
    private String subject;

    @Column(length = 500)
    private String note;

    /** yyyy-MM-dd the diary is for. */
    @Column(length = 10)
    private String diaryDate;

    @Column(nullable = false, length = 1000)
    private String imagePaths;

    private boolean published = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
