package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * PERFORMANCE FIX — Manage Students' 1-2 minute first load.
 *
 * manage-students.js used to call plain GET /api/students on its first
 * load, which returns complete Student entities. Student.photo and
 * Student.certData are @Lob LONGTEXT columns holding base64 images, and
 * base64 is ~133% the size of the original file — so a 300-student school
 * with photos on file is tens of megabytes that MySQL has to read off
 * disk, Hibernate has to materialise, Jackson has to serialise, the VPS
 * has to ship from Europe to the browser, and the browser has to parse,
 * all before the first table row can render.
 *
 * This DTO is everything that page needs to render its table, its profile
 * card and its edit form — i.e. every column on Student EXCEPT photo and
 * certData — plus a `hasPhoto` flag so the frontend knows whether to point
 * an <img> at GET /api/students/{regNo}/photo or fall straight to its
 * generated initials avatar.
 *
 * Note what is deliberately KEPT here that StudentSyncDTO drops:
 * hasSiblings and otherFeesData are @Lob-annotated but in practice hold a
 * few hundred bytes of JSON each, not images — the edit form and the
 * sibling display both need them, and excluding them would cost far more
 * in extra round trips than it saves in bytes.
 *
 * Populated via a JPQL constructor expression (see
 * StudentRepository#findListBySchoolId), so the two excluded LOB columns
 * are never read off disk at all for this query — `hasPhoto` is computed
 * in SQL from whether photo is null/empty, which does not materialise the
 * column value.
 */
@Data
@NoArgsConstructor
public class StudentListDTO {

    private Long id;
    private String regNo;
    private String fullName;
    private String rollNo;
    private String studentClass;
    private String section;
    private Date admissionDate;
    private String gender;
    private Date dob;
    private String age;
    private String studentBform;
    private String medicalIssues;

    private String orphanStatus;
    private String previousSchool;
    private String previousClass;

    private String guardianName;
    private String guardianRole;
    private String guardianCnic;

    private String phone1;
    private String phone2;
    private String permanentAddress;
    private String mailingAddress;

    private Double standardFee;
    private Double admissionFee;
    private Double tuitionDiscount;
    private Double transportDiscount;
    private Double siblingDiscount;
    private String transportMode;
    private String transportType;
    private Double transportFee;
    private Double netPayable;
    private String otherFeesData;

    private Boolean isLifetime;
    private String discountExpiry;

    private String status;

    private String graduatedDate;
    private Integer graduatedYear;
    private String graduatedClass;
    private String graduatedSection;

    private String siblingGroupId;
    private Boolean isSibling;
    private String siblingOf;
    private String hasSiblings;

    private String droppedDate;

    private Double arrears;
    private Boolean voucherCustomFees;
    private String voucherCustomFeesMonth;
    private Double voucherBulkDiscount;
    private String voucherNote;

    /** True when this student has a photo on file, without shipping it. */
    private Boolean hasPhoto;

    /**
     * New file-storage rows contain a short relative path here.  It is
     * included separately from hasPhoto so the frontend can build the photo
     * URL without ever receiving legacy base64 content in the roster query.
     * For legacy rows this remains null and the photo endpoint still serves
     * the old inline base64 value.
     */
    private String photoPath;

    public StudentListDTO(
            Long id, String regNo, String fullName, String rollNo, String studentClass,
            String section, Date admissionDate, String gender, Date dob, String age,
            String studentBform, String medicalIssues, String orphanStatus,
            String previousSchool, String previousClass, String guardianName,
            String guardianRole, String guardianCnic, String phone1, String phone2,
            String permanentAddress, String mailingAddress, Double standardFee,
            Double admissionFee, Double tuitionDiscount, Double transportDiscount,
            Double siblingDiscount, String transportMode, String transportType,
            Double transportFee, Double netPayable, String otherFeesData,
            Boolean isLifetime, String discountExpiry, String status,
            String graduatedDate, Integer graduatedYear, String graduatedClass,
            String graduatedSection, String siblingGroupId, Boolean isSibling,
            String siblingOf, String hasSiblings, String droppedDate, Double arrears,
            Boolean voucherCustomFees, String voucherCustomFeesMonth,
             Double voucherBulkDiscount, String voucherNote, Boolean hasPhoto,
             String photoPath) {
        this.id = id;
        this.regNo = regNo;
        this.fullName = fullName;
        this.rollNo = rollNo;
        this.studentClass = studentClass;
        this.section = section;
        this.admissionDate = admissionDate;
        this.gender = gender;
        this.dob = dob;
        this.age = age;
        this.studentBform = studentBform;
        this.medicalIssues = medicalIssues;
        this.orphanStatus = orphanStatus;
        this.previousSchool = previousSchool;
        this.previousClass = previousClass;
        this.guardianName = guardianName;
        this.guardianRole = guardianRole;
        this.guardianCnic = guardianCnic;
        this.phone1 = phone1;
        this.phone2 = phone2;
        this.permanentAddress = permanentAddress;
        this.mailingAddress = mailingAddress;
        this.standardFee = standardFee;
        this.admissionFee = admissionFee;
        this.tuitionDiscount = tuitionDiscount;
        this.transportDiscount = transportDiscount;
        this.siblingDiscount = siblingDiscount;
        this.transportMode = transportMode;
        this.transportType = transportType;
        this.transportFee = transportFee;
        this.netPayable = netPayable;
        this.otherFeesData = otherFeesData;
        this.isLifetime = isLifetime;
        this.discountExpiry = discountExpiry;
        this.status = status;
        this.graduatedDate = graduatedDate;
        this.graduatedYear = graduatedYear;
        this.graduatedClass = graduatedClass;
        this.graduatedSection = graduatedSection;
        this.siblingGroupId = siblingGroupId;
        this.isSibling = isSibling;
        this.siblingOf = siblingOf;
        this.hasSiblings = hasSiblings;
        this.droppedDate = droppedDate;
        this.arrears = arrears;
        this.voucherCustomFees = voucherCustomFees;
        this.voucherCustomFeesMonth = voucherCustomFeesMonth;
        this.voucherBulkDiscount = voucherBulkDiscount;
        this.voucherNote = voucherNote;
        this.hasPhoto = hasPhoto;
        this.photoPath = photoPath;
    }
}
