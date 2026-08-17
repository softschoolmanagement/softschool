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

    // Discount validity — like the sibling/dropped-date fields above, these
    // were frontend-only (never in API_STUDENT_FIELDS, never on this
    // entity), so the value only ever lived as long as the page stayed
    // open: reload/resync silently wiped it, which is what made the
    // "Discount Valid Until" date look reset every time Edit was reopened.
    private Boolean isLifetime;      // true = discount never expires
    private String discountExpiry;   // "yyyy-MM-dd"; ignored when isLifetime is true

    private String status; // active, graduated, dropped

    // Graduation snapshot — set once by the "Promote All" flow when a
    // student is promoted out of the school's last configured class (see
    // manage-students.js confirmPromotion()). Previously these were
    // frontend-only (never sent to the backend, never on this entity), so
    // the Archive Center's "graduated" roster looked right for the rest of
    // that browser session and then lost its class/section/date on the next
    // refresh, tab, or background sync — same root cause as the sibling and
    // droppedDate fields above.
    private String graduatedDate;    // "yyyy-MM-dd"
    private Integer graduatedYear;
    private String graduatedClass;   // class the student graduated FROM
    private String graduatedSection; // section the student graduated FROM

    // --- Sibling linking (see manage-students.js "Mark as Sibling" flow) ---
    // These were previously frontend-only (kept in the browser's in-memory
    // mirror), which meant a sibling link looked saved but disappeared on
    // refresh, in another tab, or on the next background sync — it was never
    // actually written to the database. Persisting them here fixes that.
    private String siblingGroupId;   // shared "00X" family code
    private Boolean isSibling;       // true once this student is linked into a sibling group
    private String siblingOf;        // display string, e.g. "Sibling of Ali and Ahmed"

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String hasSiblings;      // JSON-encoded [{name, regNo}, ...] of the rest of the family

    // Date a student was removed via the "Delete"/soft-delete flow (Archive
    // Center → Dropped Out). Previously this was only ever set in the
    // browser's in-memory copy and never sent anywhere the backend could
    // store it — the soft-delete endpoint only ever touched `status`. That
    // meant the value shown in the Archive Center depended entirely on
    // whatever stale in-memory data happened to still be around, which is
    // what made it show the wrong (e.g. admission) date after a refresh or
    // resync. Stored as "yyyy-MM-dd" text, same format it's generated in.
    private String droppedDate;

    // --- Voucher / arrears state (manage-finance.js) ---
    // BUGFIX — "arrears missing / flips between values on refresh, next
    // month's voucher total mismatches Pending": none of these five fields
    // existed on this entity, so saveStudentsCache()'s PUT of the full
    // student object was silently dropping them (Jackson's
    // @JsonIgnoreProperties(ignoreUnknown = true) discards any JSON
    // property with no matching field) — they only ever lived in that one
    // browser tab's in-memory _studentsCache. Every ~10s, the live-sync
    // poll (refreshStudentsCache) re-fetched the roster from this table and
    // overwrote each student with the (arrears-less) DB copy — feePayments
    // was the only field explicitly carried over across that overwrite, so
    // arrears/voucher state reverted to 0/blank on the very next poll,
    // right after "Generate Monthly Fee" had just locked it in. The
    // already-persisted voucher SNAPSHOT (Finance.TYPE_VOUCHER, a separate
    // table) still remembered the correct, arrears-inclusive total — which
    // is exactly why "Generated" stayed high while the live "Pending" row
    // (recomputed from this now-empty field) came up short and unstable.
    private Double arrears = 0.0;                 // rolled-over unpaid balance, locked in at "Generate"
    private Boolean voucherCustomFees = false;     // whether an "Edit Voucher" custom breakdown is active
    private String voucherCustomFeesMonth;         // which monthKey that custom breakdown belongs to
    private Double voucherBulkDiscount = 0.0;      // one-time "Add to Voucher" discount
    private String voucherNote;                    // custom note printed on the voucher
}