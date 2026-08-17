package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * A lifetime snapshot of what was actually paid out to a staff member,
 * captured the moment that staff member is deleted (see
 * StaffController#delete()).
 *
 * WHY THIS EXISTS:
 * StaffController#delete() wipes every Finance row (SALARY, ADVANCE, and
 * the STAFF_BONUS/STAFF_FINE bulk-list entries) tied to the deleted
 * staffId — see that method's docs for why (a reused staffId must never
 * "inherit" a dead staff member's old paid-salary/bonus/fine history).
 * Without archiving something first, that money simply vanished from
 * every dashboard total the instant the staff row was deleted, even
 * though it was genuinely paid out and belongs in Net Expenses forever
 * after. This entity is that archive: one row per deleted staff member,
 * created right before their Finance rows are wiped, holding exactly
 * enough to answer "how much did we ever pay this person" without
 * needing their Finance history to still exist.
 *
 * NOT scoped to any month — see FinanceController#getDropoutStaffSalaries
 * and main.js's dashboard, which fold `total` into the CURRENT month's Net
 * Expenses only (there's no honest way to say which past month it
 * "belongs" to).
 */
@Entity
@Table(name = "dropout_staff", indexes = {
        @Index(name = "idx_dropout_staff_school", columnList = "schoolId")
})
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DropoutStaffRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which school this deleted staff member belonged to (School.schoolId).
    @Column(nullable = false)
    private String schoolId;

    // The deleted staff member's old public-facing staffId (e.g. "PSC_S_1")
    // and name, kept purely for display — this id may since have been
    // reused by a new hire, so it must never be used to look anything up.
    private String staffId;
    private String staffName;

    // Lifetime SALARY rows' amountPaid, summed across every month this
    // staff member was ever paid.
    private Double paidSalaryTotal = 0.0;

    // Lifetime ADVANCE rows that were still outstanding (never settled
    // into a payroll run) at the time of deletion — cash that had already
    // left the building but wasn't yet folded into a SALARY row's
    // amountPaid, so it isn't double-counted with paidSalaryTotal above.
    private Double advancePaidTotal = 0.0;

    // Lifetime STAFF_BONUS entries paid to this staff member.
    private Double bonusPaidTotal = 0.0;

    // Lifetime STAFF_FINE entries recorded against this staff member — kept
    // for record-keeping only; fines are a deduction, not a payout, so they
    // are NOT added into `total` below.
    private Double finesTotal = 0.0;

    // paidSalaryTotal + advancePaidTotal + bonusPaidTotal — the figure the
    // dashboard actually adds into Net Expenses.
    private Double total = 0.0;

    @Temporal(TemporalType.TIMESTAMP)
    private Date deletedAt = new Date();
}