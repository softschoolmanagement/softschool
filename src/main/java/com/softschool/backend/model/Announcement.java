package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One announcement / message. Used in both directions:
 *   senderType ADMIN   -> posted by the school admin
 *   senderType TEACHER -> sent from the Teacher Portal "Send" area
 *
 * audience values:
 *   "teachers" -> every teacher in the school
 *   "teacher"  -> one teacher (targetStaffId)
 *   "admin"    -> the school admin / principal inbox
 *   "class"    -> a class's students & parents (className) — stored now,
 *                 shown once the student/parent portal reads it
 *
 * Every row carries schoolId and every query is scoped by it, like Staff,
 * Finance and Attendance.
 */
@Entity
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Table(name = "announcement",
       indexes = {
           @Index(name = "idx_ann_school_created", columnList = "schoolId,createdAt"),
           @Index(name = "idx_ann_school_audience", columnList = "schoolId,audience")
       })
public class Announcement {

    public static final String SENDER_ADMIN = "ADMIN";
    public static final String SENDER_TEACHER = "TEACHER";
    public static final String AUD_TEACHERS = "teachers";
    public static final String AUD_TEACHER = "teacher";
    public static final String AUD_ADMIN = "admin";
    public static final String AUD_CLASS = "class";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 10)
    private String senderType;

    // staffId for teachers, "admin" for the school admin
    private String senderId;
    private String senderName;

    @Column(nullable = false, length = 10)
    private String audience;

    private String targetStaffId;
    private String className;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(length = 10)
    private String priority = "normal"; // "normal" | "urgent"

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
