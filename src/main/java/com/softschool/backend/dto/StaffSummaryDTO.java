package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PERFORMANCE FIX — "Staff" dashboard card (main.js) AND the Attendance
 * page's staff roster cards (attendance.js) were both slow. Same root
 * cause as StudentSummaryDTO: Staff.photo / Staff.agreementData /
 * Staff.classAssignments / Staff.inchargeAssignments are @Lob LONGTEXT
 * columns, eagerly fetched by default.
 *
 * main.js's dashboard only ever needs staffId + salary (to derive
 * fines/headcount); attendance.js's loadRealStaff() additionally needs
 * name/type/role/subjects/job to render the Teaching / Non-Teaching
 * roster cards (attendance.js falls back to 'Support' for the
 * Non-Teaching "department" label — Staff has no department column, so
 * there's nothing extra to select for that one). None of the fields
 * added here are @Lob columns, so a JPQL constructor expression is used
 * to select exactly this set instead of the whole row (photo/
 * agreementData/classAssignments/inchargeAssignments are never read off
 * disk or sent over the wire for this endpoint).
 *
 * `fines` / `absentDaysThisMonth` are filled in afterwards by
 * StaffController#applyAbsenceFinesToSummary — same derived, "right now"
 * figures as on the full Staff entity, just computed onto this lighter
 * object instead.
 */
@Data
@NoArgsConstructor
public class StaffSummaryDTO {
    private String staffId;
    private double salary;
    private double fines;
    private Integer absentDaysThisMonth;
    private String type;       // "Teaching" / "Non-Teaching"
    private String name;
    private String role;       // Teaching staff's role (e.g. "Teacher")
    private String subjects;   // Teaching staff's department/subjects
    private String job;        // Non-Teaching staff's job title

    public StaffSummaryDTO(String staffId, double salary, String type, String name,
                            String role, String subjects, String job) {
        this.staffId = staffId;
        this.salary = salary;
        this.type = type;
        this.name = name;
        this.role = role;
        this.subjects = subjects;
        this.job = job;
    }
}
