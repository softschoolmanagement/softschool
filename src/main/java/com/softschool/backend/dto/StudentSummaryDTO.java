package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * PERFORMANCE FIX — "Student" dashboard card (main.js) AND the Attendance
 * page's student roster cards (attendance.js) were both slow.
 *
 * Student.photo / Student.certData / Student.otherFeesData are @Lob
 * LONGTEXT columns holding base64 images/certificates — often hundreds of
 * KB each. Hibernate fetches @Lob string columns EAGERLY by default (no
 * bytecode-enhancement lazy loading configured in this project), so every
 * plain `findBySchoolId(...)` used to pull down every one of those blobs
 * for every student — even though:
 *   - main.js's dashboard only ever needs regNo/status/admissionDate/
 *     admissionFee to compute the Student attendance-ring card + Total
 *     Revenue, and
 *   - attendance.js's loadRealStudents() only ever needs regNo/fullName/
 *     studentClass/section/guardianName/status to render the roster
 *     cards and class list.
 *
 * fullName/studentClass/section/guardianName were added here (none of
 * them are @Lob columns) so attendance.js can be pointed at this same
 * lightweight endpoint instead of GET /api/students, without losing any
 * field its cards actually render.
 *
 * This DTO is populated via a JPQL constructor expression
 * (`select new ...StudentSummaryDTO(...)`), which tells Hibernate to only
 * SELECT these columns at the SQL level — the LOB columns are never read
 * off disk or sent over the wire for this endpoint.
 */
@Data
@NoArgsConstructor
public class StudentSummaryDTO {
    private String regNo;
    private String status;
    private Date admissionDate;
    private Double admissionFee;
    private String fullName;
    private String studentClass;
    private String section;
    private String guardianName;

    public StudentSummaryDTO(String regNo, String status, Date admissionDate, Double admissionFee,
                              String fullName, String studentClass, String section, String guardianName) {
        this.regNo = regNo;
        this.status = status;
        this.admissionDate = admissionDate;
        this.admissionFee = admissionFee;
        this.fullName = fullName;
        this.studentClass = studentClass;
        this.section = section;
        this.guardianName = guardianName;
    }
}
