package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

@Entity
@Table(name = "students",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "regNo"}))
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which school this student belongs to (School.schoolId, e.g. "SS_77_1").
    // Every read/write on this entity is scoped by this field so each school
    // only ever sees its own students — see StudentController.
    @Column(nullable = false)
    private String schoolId;

    // regNo (e.g. "PSC_3") is only unique WITHIN a school now, not globally —
    // two different schools can both mint "PSC_1" if they share a prefix, so
    // the uniqueness is enforced on (schoolId, regNo) above instead of here.
    private String regNo;

    private String fullName;
    private String rollNo;
    private String studentClass;
    private String section;
    
    @Temporal(TemporalType.DATE)
    private Date admissionDate;

    private String gender;
    
    @Temporal(TemporalType.DATE)
    private Date dob;
    
    private String age;
    private String studentBform;
    private String medicalIssues;

    // --- NEW FIELDS ADDED HERE ---
    private String orphanStatus;   // Stores "Orphan" or "Not Orphan"
    private String previousSchool; // Name of the last school
    private String previousClass;  // Last class attended
    // ------------------------------

    // Guardian Info
    private String guardianName;
    private String guardianRole;
    private String guardianCnic;

    // Contact Info
    private String phone1;
    private String phone2;
    private String permanentAddress;
    private String mailingAddress;

    // Images (Stored as Base64 LongText)
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String photo;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String certData; 

    // Financials
    private Double standardFee;
    private Double admissionFee;
    private Double tuitionDiscount;
    private Double transportDiscount;
    private Double siblingDiscount;
    private String transportMode;
    private String transportType;
    private Double transportFee;
    private Double netPayable;
    
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String otherFeesData; 

    private String status; // active, graduated, dropped
}