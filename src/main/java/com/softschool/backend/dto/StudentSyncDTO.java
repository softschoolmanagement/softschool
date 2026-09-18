package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * PERFORMANCE FIX — Manage Students (manage-students.js) polls
 * GET /api/students every 6 seconds, and Manage Finance (manage-finance.js)
 * polls the SAME plain endpoint every 10 seconds, for as long as either
 * page stays open (see LIVE_SYNC_INTERVAL_MS in both files). Every one of
 * those background polls re-pulled every student's full record — photo /
 * certData / hasSiblings LONGTEXT blobs included — even though a
 * background sync only needs to catch up on fields that actually change
 * between polls (fees, discounts, arrears, voucher state, class/section
 * moves). photo/certData/hasSiblings essentially never change between one
 * 6-10 second poll and the next, but were being re-fetched and re-parsed
 * every single cycle anyway — the dominant ongoing cost on both pages,
 * separate from (and bigger than) the initial page-load cost.
 *
 * This DTO carries everything both pages' sync loops actually read on a
 * refresh (see refreshStudentsCache() in manage-finance.js and the
 * live-sync handler in manage-students.js) MINUS the three heavy LOB
 * columns. The very first load of either page still calls plain
 * GET /api/students once, to get photo/certData/hasSiblings — every poll
 * after that calls GET /api/students/sync instead, and the frontend merges
 * the result into the existing cache, preserving photo/certData/
 * hasSiblings from the prior cache entry exactly the way feePayments is
 * already preserved (see the BUGFIX comment on refreshStudentsCache) —
 * so a poll can never silently wipe them again.
 */
@Data
@NoArgsConstructor
public class StudentSyncDTO {
    private String regNo;
    private String status;
    private String fullName;
    private String studentClass;
    private String section;
    private String guardianName;
    private Date admissionDate;

    // Fees / discounts / voucher state — the fields that actually change
    // between polls and are what Manage Finance's sync loop exists to catch.
    private Double standardFee;
    private Double admissionFee;
    private Double tuitionDiscount;
    private Double transportDiscount;
    private Double siblingDiscount;
    private String transportMode;
    private String transportType;
    private Double transportFee;
    private Double netPayable;
    private String otherFeesData;

    private Boolean isLifetime;
    private String discountExpiry;

    private Double arrears;
    private Boolean voucherCustomFees;
    private String voucherCustomFeesMonth;
    private Double voucherBulkDiscount;
    private String voucherNote;

    private String siblingGroupId;
    private Boolean isSibling;
    private String siblingOf;

    public StudentSyncDTO(String regNo, String status, String fullName, String studentClass,
                           String section, String guardianName, Date admissionDate,
                           Double standardFee, Double admissionFee, Double tuitionDiscount,
                           Double transportDiscount, Double siblingDiscount, String transportMode,
                           String transportType, Double transportFee, Double netPayable,
                           String otherFeesData, Boolean isLifetime, String discountExpiry,
                           Double arrears, Boolean voucherCustomFees, String voucherCustomFeesMonth,
                           Double voucherBulkDiscount, String voucherNote, String siblingGroupId,
                           Boolean isSibling, String siblingOf) {
        this.regNo = regNo;
        this.status = status;
        this.fullName = fullName;
        this.studentClass = studentClass;
        this.section = section;
        this.guardianName = guardianName;
        this.admissionDate = admissionDate;
        this.standardFee = standardFee;
        this.admissionFee = admissionFee;
        this.tuitionDiscount = tuitionDiscount;
        this.transportDiscount = transportDiscount;
        this.siblingDiscount = siblingDiscount;
        this.transportMode = transportMode;
        this.transportType = transportType;
        this.transportFee = transportFee;
        this.netPayable = netPayable;
        this.otherFeesData = otherFeesData;
        this.isLifetime = isLifetime;
        this.discountExpiry = discountExpiry;
        this.arrears = arrears;
        this.voucherCustomFees = voucherCustomFees;
        this.voucherCustomFeesMonth = voucherCustomFeesMonth;
        this.voucherBulkDiscount = voucherBulkDiscount;
        this.voucherNote = voucherNote;
        this.siblingGroupId = siblingGroupId;
        this.isSibling = isSibling;
        this.siblingOf = siblingOf;
    }
}
