package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * The weekly timetable of one class. The backend had no timetable data before the parent portal, so
 * this is a new table. The admin side writes it with PUT /api/parent-admin/timetable/{className};
 * the parent portal reads it. payloadJson is stored as sent, e.g.
 *   { "periods":[{"num":1,"time":"8:30 – 9:15"},...],
 *     "days":{"Monday":["English","Maths",...], "Tuesday":[...]} }
 */
@Entity
@Data
@Table(name = "class_timetable",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "className"}))
public class ClassTimetable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    /** Class label as the school writes it, e.g. "Class 5 - B". */
    @Column(nullable = false, length = 100)
    private String className;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String payloadJson;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
