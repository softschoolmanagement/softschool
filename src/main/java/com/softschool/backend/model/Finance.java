package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * ONE table for ALL finance records — student fee ledgers, individual
 * student fines, staff salary payments, and staff salary advances. This
 * replaces the previous StudentFinance / FineRecord / SalaryRecord model
 * classes (and their 3 separate repositories) with a single wide table,
 * discriminated by {@link #recordType}.
 *
 * WHY ONE TABLE:
 * The frontend (manage-finance.js) treats all of these as "finance" data
 * and the four record types share a lot of shape (amounts, month, status,
 * timestamps), so folding them into one entity + one repository + one
 * controller keeps the whole finance module in 3 files instead of 8,
 * while every query is still just as targeted (filtered by recordType).
 *
 * RECORD TYPES (see the {@link #recordType} constants below):
 *   STUDENT_FEE -> one row per (schoolId, regNo, monthKey). The monthly fee
 *                  ledger/master row for a student (tuition, transport,
 *                  arrears, running fine total, discounts, paid/remaining).
 *                  Mirrors the old StudentFinance entity.
 *   FINE        -> one row per individual fine event applied to a student
 *                  (amount, reason, Pending/Paid). Mirrors the old
 *                  FineRecord entity. Many FINE rows roll up into one
 *                  STUDENT_FEE row's fineAmount/fineReason.
 *   SALARY      -> one row per staff salary payment for a month. Mirrors
 *                  the old SalaryRecord entity. paymentStatus is now
 *                  derived by {@link #calculateSalaryDue()}: "Paid" only
 *                  when amountPaid has caught up to totalDue, otherwise
 *                  "Partial" or "Pending" — see that method's docs.
 *   ADVANCE     -> one row per staff salary advance. Also uses the old
 *                  SalaryRecord shape, but paymentStatus starts as
 *                  "Advance" and flips to "Settled" once a SALARY payment
 *                  consumes it (see FinanceController#paySalary).
 *
 * SCHOOL SCOPING:
 * Every row carries schoolId (School.schoolId, e.g. "SS_77_1") and every
 * query in FinanceRepository is filtered by it — the same pattern used for
 * Staff.schoolId / StaffController and Student.schoolId / StudentController
 * — so one school can never see or touch another school's fee ledgers,
 * fines, salaries, or advances, even if a regNo/staffId happens to collide
 * across schools.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) so any stray fields the
 * frontend still sends don't cause a 400 on save.
 */
@Entity
@Table(
    name = "finance",
    indexes = {
        @Index(name = "idx_finance_school_type_month", columnList = "schoolId,recordType,monthKey"),
        @Index(name = "idx_finance_school_regno", columnList = "schoolId,regNo"),
        @Index(name = "idx_finance_school_staffid", columnList = "schoolId,staffId")
    }
)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Finance {

    public static final String TYPE_STUDENT_FEE = "STUDENT_FEE";
    public static final String TYPE_FINE        = "FINE";
    public static final String TYPE_SALARY      = "SALARY";
    public static final String TYPE_ADVANCE     = "ADVANCE";

    // ---- "Bulk list" record types (manage-finance.js's /custom-fees,
    // /staff-bonus, /staff-fines, /expenses, /vouchers endpoints) ----
    // Each of these is saved/read as a whole JSON array from the frontend
    // (GET returns the array, PUT { items: [...] } replaces it), and every
    // item can have a different shape depending on the page. Rather than
    // adding a dozen narrow columns for each one, each item is stored
    // verbatim as JSON in {@link #payloadJson} on its own row, one row per
    // item, discriminated by recordType exactly like the other types above.
    public static final String TYPE_EXPENSE      = "EXPENSE";
    public static final String TYPE_CUSTOM_FEE   = "CUSTOM_FEE";
    public static final String TYPE_STAFF_BONUS  = "STAFF_BONUS";
    public static final String TYPE_STAFF_FINE   = "STAFF_FINE";
    public static final String TYPE_VOUCHER      = "VOUCHER";
    // Bulk-saved staff advances (manage-finance.js's saveAdvanceRecords()).
    // Deliberately NOT the same as TYPE_ADVANCE, which is written one row
    // at a time by POST /salary/advance and consumed by the salary-payment
    // math in FinanceController#paySalary. Keeping them separate means a
    // bulk PUT to /staff-advances can never clobber or double-count a real
    // advance that's already been deducted from someone's salary; GET
    // /staff-advances merges both sets back together for the frontend.
    public static final String TYPE_STAFF_ADVANCE_BULK = "STAFF_ADVANCE_BULK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which school this record belongs to (School.schoolId, e.g. "SS_77_1").
    @Column(nullable = false)
    private String schoolId;

    // STUDENT_FEE | FINE | SALARY | ADVANCE — see class docs above.
    @Column(nullable = false)
    private String recordType;

    // "2025-01" style month bucket, shared by all record types.
    private String monthKey;

    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt = new Date();

    // ---- Student linkage (STUDENT_FEE, FINE) ----
    private String regNo;
    private String studentName;
    private String studentClass;
    private String section;
    private String guardianName;

    // ---- Staff linkage (SALARY, ADVANCE) ----
    private String staffId;

    // ---- STUDENT_FEE fields: the monthly fee ledger/master row ----
    private Double baseTuitionFee = 0.0;
    private Double transportFee = 0.0;
    private Double otherCharges = 0.0;   // includes rolled-over arrears
    private Double fineAmount = 0.0;     // running total of this month's CURRENTLY UNPAID fines
                                          // (shrinks as individual fines get settled — still used
                                          // for "Unpaid Fine" badges/defaulter lists elsewhere)
    // BUGFIX — "Expected Fees drops the moment a fine is paid": netPayable
    // used to be computed straight off `fineAmount` above, which is reduced
    // to 0 the instant a fine is settled (see FinanceController#removeFineFromMaster
    // / #settleCoveredFineRecords). That made Expected Fees / "Total with
    // Fine" on both the Dashboard and Manage Finance visibly SHRINK right
    // after a fine got paid, even though that fine was genuinely billed and
    // genuinely collected. `totalFineCharged` is a separate running total —
    // every fine ever added this month (see FinanceController#addFine), and
    // it is NEVER reduced when a fine is later paid off. calculateNetPayable()
    // below now uses this field, so Expected Fees stays permanently
    // inclusive of every fine charged this month regardless of payment
    // status; only Pending (remainingBalance, driven by paidAmount) still
    // correctly drops to 0 once the bill — fine included — is actually paid.
    private Double totalFineCharged = 0.0;
    private String fineReason;
    private Double totalDiscountApplied = 0.0;

    @Temporal(TemporalType.DATE)
    private Date discountExpiryDate;

    private Double netPayable = 0.0;
    private Double paidAmount = 0.0;
    private Double remainingBalance = 0.0;

    // STUDENT_FEE: "Paid" | "Partial" | "Pending"
    // FINE / ADVANCE: "Pending" | "Paid" | "Settled"
    // SALARY: "Paid" | "Partial" | "Pending" (see calculateSalaryDue())
    private String paymentStatus;

    @Temporal(TemporalType.TIMESTAMP)
    private Date lastTransactionDate;

    // ---- FINE fields (individual fine event, recordType = FINE) ----
    // Also doubles as the advance amount when recordType = ADVANCE.
    private Double amount;
    private String reason;
    private String applyDate;
    private String applyTime;
    private String payDate;
    private String payTime;

    // ---- SALARY fields (recordType = SALARY) ----
    private Double baseSalary;
    private Double bonus;
    private Double fines;             // manual fine deducted in this payroll run
    private Double securityDeducted;
    private Double advanceDeducted;   // advance(s) settled against this month's pay

    // netPaid = the NEW cash handed over in *this* transaction only. It does
    // NOT include the advance — the advance was already physically paid to
    // the staff member earlier in the month, so it must not be paid out
    // twice. This is what FinanceController#paySalary writes when it
    // processes a (possibly partial) payment.
    private Double netPaid;

    // ---- Derived SALARY totals — set by calculateSalaryDue(), never by
    // hand. Kept as real columns (not computed on read) so salary history
    // and reports can query/sort/filter on them directly. ----
    private Double grossSalary;    // baseSalary + bonus
    private Double totalDue;       // grossSalary - fines - securityDeducted
    private Double amountPaid;     // advanceDeducted + netPaid ("Paid" = advance + current payment)
    private Double pendingAmount;  // max(0, totalDue - amountPaid)

    private LocalDateTime paymentDate;

    // ---- Generic payload for the "bulk list" record types above ----
    // Raw JSON of one list item exactly as the frontend sent it (e.g. one
    // expense: {"description":"...","amount":500,"date":"...","time":"...",
    // "monthKey":"2025-01"}). Only populated for TYPE_EXPENSE, TYPE_CUSTOM_FEE,
    // TYPE_STAFF_BONUS, TYPE_STAFF_FINE, TYPE_VOUCHER, TYPE_STAFF_ADVANCE_BULK.
    @Lob
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    /**
     * STUDENT_FEE math — identical logic to the old StudentFinance
     * entity's calculateNetPayable().
     */
    public void calculateNetPayable() {
        double tuition = (this.baseTuitionFee != null) ? this.baseTuitionFee : 0.0;
        double transport = (this.transportFee != null) ? this.transportFee : 0.0;
        double other = (this.otherCharges != null) ? this.otherCharges : 0.0;
        // BUGFIX — use the PERMANENT fine total (totalFineCharged), not the
        // shrinking "currently unpaid" fineAmount, so Expected Fees /
        // netPayable never drops just because a fine got paid off. See the
        // field doc on totalFineCharged above.
        double fineTotal = (this.totalFineCharged != null) ? this.totalFineCharged : 0.0;
        double discount = (this.totalDiscountApplied != null) ? this.totalDiscountApplied : 0.0;
        double paid = (this.paidAmount != null) ? this.paidAmount : 0.0;

        double gross = tuition + transport + other + fineTotal;
        this.netPayable = Math.max(0.0, gross - discount);
        // Pending correctly reaches 0 once paidAmount catches up to
        // netPayable — including the fine, since paying it off (whether via
        // the general bill payment or the individual "Pay Fine" button) adds
        // that money into paidAmount rather than removing it from netPayable.
        this.remainingBalance = Math.max(0.0, this.netPayable - paid);

        if (this.remainingBalance <= 0.01) {
            this.paymentStatus = "Paid";
        } else if (paid > 0) {
            this.paymentStatus = "Partial";
        } else {
            this.paymentStatus = "Pending";
        }
    }

    /**
     * SALARY math (recordType = SALARY). Mirrors calculateNetPayable()'s
     * role for STUDENT_FEE: called once every field it depends on
     * (baseSalary, bonus, fines, securityDeducted, advanceDeducted,
     * netPaid) has been set, and it derives grossSalary/totalDue/
     * amountPaid/pendingAmount/paymentStatus from them. Never set those
     * derived fields directly — always go through this method so a Fine
     * or an Advance can never drift out of sync with the totals.
     *
     * THE LOGIC:
     *   1. Total Due   = Gross Salary (baseSalary + bonus) - Fine, less
     *                    any security-deposit installment withheld this
     *                    month (that money isn't owed to the staff member
     *                    right now, so it can't count toward what's due).
     *                    A Fine added before this is called always comes
     *                    straight off Total Due.
     *   2. Paid Amount = Advance + current payment (netPaid). The advance
     *                    is a pre-payment already in the staff member's
     *                    hands, so it counts as paid even though it was
     *                    disbursed earlier in the month.
     *   3. Pending      = max(0, Total Due - Paid Amount).
     *   4. Status is "Paid" ONLY when Pending is ~0, i.e. Paid Amount has
     *      actually caught up to Total Due — never just because a SALARY
     *      row exists.
     */
    public void calculateSalaryDue() {
        double gross = nz(this.baseSalary) + nz(this.bonus);
        this.grossSalary = gross;

        double fine = nz(this.fines);
        double security = nz(this.securityDeducted);
        this.totalDue = Math.max(0.0, gross - fine - security);

        double advance = nz(this.advanceDeducted);
        double current = nz(this.netPaid);
        this.amountPaid = advance + current;

        this.pendingAmount = Math.max(0.0, this.totalDue - this.amountPaid);

        if (this.pendingAmount <= 0.01) {
            this.paymentStatus = "Paid";
        } else if (this.amountPaid > 0) {
            this.paymentStatus = "Partial";
        } else {
            this.paymentStatus = "Pending";
        }
    }

    private static double nz(Double d) {
        return d != null ? d : 0.0;
    }

    /** Stamps applyDate/applyTime with "now", same format the old FineRecord constructor used. */
    public void stampApplyNow() {
        LocalDateTime now = LocalDateTime.now();
        this.applyDate = now.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        this.applyTime = now.format(DateTimeFormatter.ofPattern("hh:mm a"));
    }

    /** Stamps payDate/payTime with "now", same format the old FinanceController.markFinePaid used. */
    public void stampPayNow() {
        LocalDateTime now = LocalDateTime.now();
        this.payDate = now.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        this.payTime = now.format(DateTimeFormatter.ofPattern("hh:mm a"));
    }
}