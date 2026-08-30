package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PERFORMANCE FIX — "Staff" dashboard card is slow. Same root cause as
 * StudentSummaryDTO: Staff.photo / Staff.agreementData / Staff.classAssignments
 * / Staff.inchargeAssignments are @Lob LONGTEXT columns, eagerly fetched by
 * default. main.js's dashboard only ever needs staffId + salary (to derive
 * fines/headcount), so a JPQL constructor expression is used to select just
 * those two columns instead of the whole row.
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

    public StaffSummaryDTO(String staffId, double salary) {
        this.staffId = staffId;
        this.salary = salary;
    }
}
