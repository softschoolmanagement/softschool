package com.softschool.backend.model;

import java.time.LocalTime;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Data
@Table(name = "attendance")
public class Attendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which school this record belongs to (School.schoolId, e.g. "SS_77_5").
    // Required on every record so that the same memberId (e.g. a staffId or
    // regNo) in two different schools never collides — all lookups below
    // are scoped by (memberId/memberType, date, schoolId) together.
    private String schoolId;

    private String memberId;
    private String memberName;
    private String memberType;
    private String className;
    private String section;
    private String role;
    
    @Column(name = "attendance_date")
    private LocalDate date;
    
    private String status;
    private String reason;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String capturedPhoto;

    private LocalTime checkIn;
    private LocalTime checkOut;
}