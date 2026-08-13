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
 *                  the old SalaryRecord entity (paymentStatus = "Paid").
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
    private Double fineAmount = 0.0;     // running total of this month's fines
    private String fineReason;
    private Double totalDiscountApplied = 0.0;

    @Temporal(TemporalType.DATE)
    private Date discountExpiryDate;

    private Double netPayable = 0.0;
    private Double paidAmount = 0.0;
    private Double remainingBalance = 0.0;

    // STUDENT_FEE: "Paid" | "Partial" | "Pending"
    // FINE / ADVANCE: "Pending" | "Paid" | "Settled"
    // SALARY: "Paid" | "Advance"
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
    private Double advanceDeducted;
    private Double netPaid;

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
        double fineTotal = (this.fineAmount != null) ? this.fineAmount : 0.0;
        double discount = (this.totalDiscountApplied != null) ? this.totalDiscountApplied : 0.0;
        double paid = (this.paidAmount != null) ? this.paidAmount : 0.0;

        double gross = tuition + transport + other + fineTotal;
        this.netPayable = Math.max(0.0, gross - discount);
        // Never expose a negative balance after a payment that included a
        // fine. When the fine is auto-settled, the master fineAmount is
        // removed from netPayable while paidAmount still includes the money
        // already collected for it.
        this.remainingBalance = Math.max(0.0, this.netPayable - paid);

        if (this.remainingBalance <= 0.01) {
            this.paymentStatus = "Paid";
        } else if (paid > 0) {
            this.paymentStatus = "Partial";
        } else {
            this.paymentStatus = "Pending";
        }
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