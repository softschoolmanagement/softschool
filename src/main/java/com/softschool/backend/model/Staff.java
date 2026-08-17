package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

/**
 * A staff record (Teaching or Non-Teaching). Every row belongs to exactly
 * one school (schoolId) — see StaffController, which scopes every
 * read/write by it, the same way StudentController scopes Student rows.
 *
 * ID SYSTEM — mirrors Student.java:
 *   id      -> auto-generated internal primary key (Long), never shown in
 *              the UI.
 *   staffId -> the public-facing ID the frontend generates and displays
 *              (e.g. "PSC_S_1", from access-control.js's nextStaffId()).
 *              Only unique WITHIN a school (see the unique constraint
 *              below) — two different schools can both mint "PSC_S_1" if
 *              they share a prefix, so uniqueness is enforced on
 *              (schoolId, staffId) instead of globally.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) so any stray/derived fields
 * the frontend still sends (e.g. "fatherName", a display-only alias for
 * guardianName) don't cause a 400 on save.
 */
@Entity
@Table(name = "staff",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "staffId"}))
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which school this staff member belongs to (School.schoolId, e.g. "SS_77_1").
    @Column(nullable = false)
    private String schoolId;

    // Public-facing staff ID shown in the UI (e.g. "PSC_S_1").
    private String staffId;

    // "Teaching" or "Non-Teaching" — drives which fields below apply and
    // which directory bucket this row belongs to on the frontend.
    private String type;

    private String name;

    // Legacy fields kept for backward compatibility with any older records;
    // the current frontend doesn't populate these directly.
    private String category;
    private String role;

    private double salary;
    private String phone;
    private String gender;
    private String cnic;
    private String address;

    // Guardian info
    private String guardianType;
    private String guardianName;

    // Teaching-only
    private String qualification;
    private String classes;
    private String subjects;
    private String joined;
    private String incharge;
    private String assignedClass;
    private String assignedSection;
    private Boolean isClassIncharge;

    // JSON-stringified arrays (frontend already serializes these before sending)
    @Lob
    @Column(columnDefinition = "TEXT")
    private String classAssignments;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String inchargeAssignments;

    // Non-Teaching-only
    private String job;
    private String startTime;
    private String endTime;

    // Security deposit
    private double securityTotal;
    private double securityCollected;
    private double securityMonthly;

    private double fines;

    // FEATURE — absence-fine wiring: derived, read-only, "right now" figure
    // (never persisted — see StaffController#applyAbsenceFines, which fills
    // this in every time staff are fetched). Lets manage-finance.js's salary
    // pages and main.js's dashboard show the same absent-day count the fine
    // above was actually calculated from, instead of just a lump number.
    @Transient
    private Integer absentDaysThisMonth;

    // Photo (Base64), stored the same way Student.photo is
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String photo;

    // Staff agreement file, packed as a JSON string: {"name":..,"type":..,"data":..}
    // (frontend's toApiStaffPayload()/fromApiStaffRecord() handle packing/unpacking)
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String agreementData;
}