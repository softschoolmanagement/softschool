package com.softschool.backend.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * "Settings" entity — now ONE ROW PER SCHOOL (schoolId), not a single
 * global row. It mirrors everything the Settings page (settings.html /
 * settings.js) reads and saves for a given school:
 *   - School & contact details
 *   - Class fee structure (+ sections)
 *   - Late fee rules
 *   - Global pay variables (leave penalty / bonus)
 *   - Attendance auto-save timings
 *
 * Deleted automatically (via SchoolSettingsRepository.deleteBySchoolId)
 * whenever the parent School is permanently deleted in SuperAdminController.
 * Blocking/unblocking a school does NOT touch this table.
 */
@Entity
@Table(name = "school_settings")
public class SchoolSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Links this settings row to School.schoolId (e.g. "SS_77_12").
    // Unique + immutable once created — a school has exactly one settings row.
    @Column(name = "school_id", unique = true, nullable = false, updatable = false)
    private String schoolId;

    // ── School Info ─────────────────────────────
    private String schoolName;

    @Column(length = 1000)
    private String schoolAddress;

    private String schoolPhone;
    private String schoolPhoneAlt;
    private String schoolEmail;
    private String schoolWebsite;
    private String schoolPrincipal;
    private String schoolRegNo;

    // ── Late Fee ─────────────────────────────────
    private Boolean lateFeeEnabled = true;
    private Integer lateFeeDeadlineDay = 10;
    private String  lateFeeType = "fixed";   // "fixed" | "percent"
    private Double  lateFeeAmount = 200.0;
    private Integer lateFeeGrace = 0;

    // ── Global Pay Variables ─────────────────────
    private String  payPenaltyType = "percent"; // "percent" | "fixed"
    private Double  payPenaltyValue = 3.0;
    private Double  payBonus = 1000.0;

    // ── Attendance Auto-Save Timing (1st slot) ───
    private Integer autosave1Hour = 10;
    private Integer autosave1Minute = 0;
    private String  autosave1Meridiem = "AM";
    private Boolean autosave1Enabled = true;

    // ── Attendance Auto-Save Timing (2nd slot) ───
    private Integer autosave2Hour = 2;
    private Integer autosave2Minute = 0;
    private String  autosave2Meridiem = "PM";
    private Boolean autosave2Enabled = true;

    // ── Class Fee Structure ──────────────────────
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "school_settings_classes", joinColumns = @JoinColumn(name = "settings_id"))
    private List<ClassFee> classes = new ArrayList<>();

    public SchoolSettings() {
    }

    /** Embeddable row for one class in the fee structure grid. */
    @Embeddable
    public static class ClassFee {

        private String className;
        private Double fee;
        private Double fund;

        /** Sections stored as a comma-separated list, e.g. "A,B,Rose" */
        private String sections;

        public ClassFee() {
        }

        public ClassFee(String className, Double fee, Double fund, String sections) {
            this.className = className;
            this.fee = fee;
            this.fund = fund;
            this.sections = sections;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public Double getFee() {
            return fee;
        }

        public void setFee(Double fee) {
            this.fee = fee;
        }

        public Double getFund() {
            return fund;
        }

        public void setFund(Double fund) {
            this.fund = fund;
        }

        public String getSections() {
            return sections;
        }

        public void setSections(String sections) {
            this.sections = sections;
        }
    }

    // ── Getters & Setters ────────────────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSchoolId() {
        return schoolId;
    }

    public void setSchoolId(String schoolId) {
        this.schoolId = schoolId;
    }

    public String getSchoolName() {
        return schoolName;
    }

    public void setSchoolName(String schoolName) {
        this.schoolName = schoolName;
    }

    public String getSchoolAddress() {
        return schoolAddress;
    }

    public void setSchoolAddress(String schoolAddress) {
        this.schoolAddress = schoolAddress;
    }

    public String getSchoolPhone() {
        return schoolPhone;
    }

    public void setSchoolPhone(String schoolPhone) {
        this.schoolPhone = schoolPhone;
    }

    public String getSchoolPhoneAlt() {
        return schoolPhoneAlt;
    }

    public void setSchoolPhoneAlt(String schoolPhoneAlt) {
        this.schoolPhoneAlt = schoolPhoneAlt;
    }

    public String getSchoolEmail() {
        return schoolEmail;
    }

    public void setSchoolEmail(String schoolEmail) {
        this.schoolEmail = schoolEmail;
    }

    public String getSchoolWebsite() {
        return schoolWebsite;
    }

    public void setSchoolWebsite(String schoolWebsite) {
        this.schoolWebsite = schoolWebsite;
    }

    public String getSchoolPrincipal() {
        return schoolPrincipal;
    }

    public void setSchoolPrincipal(String schoolPrincipal) {
        this.schoolPrincipal = schoolPrincipal;
    }

    public String getSchoolRegNo() {
        return schoolRegNo;
    }

    public void setSchoolRegNo(String schoolRegNo) {
        this.schoolRegNo = schoolRegNo;
    }

    public Boolean getLateFeeEnabled() {
        return lateFeeEnabled;
    }

    public void setLateFeeEnabled(Boolean lateFeeEnabled) {
        this.lateFeeEnabled = lateFeeEnabled;
    }

    public Integer getLateFeeDeadlineDay() {
        return lateFeeDeadlineDay;
    }

    public void setLateFeeDeadlineDay(Integer lateFeeDeadlineDay) {
        this.lateFeeDeadlineDay = lateFeeDeadlineDay;
    }

    public String getLateFeeType() {
        return lateFeeType;
    }

    public void setLateFeeType(String lateFeeType) {
        this.lateFeeType = lateFeeType;
    }

    public Double getLateFeeAmount() {
        return lateFeeAmount;
    }

    public void setLateFeeAmount(Double lateFeeAmount) {
        this.lateFeeAmount = lateFeeAmount;
    }

    public Integer getLateFeeGrace() {
        return lateFeeGrace;
    }

    public void setLateFeeGrace(Integer lateFeeGrace) {
        this.lateFeeGrace = lateFeeGrace;
    }

    public String getPayPenaltyType() {
        return payPenaltyType;
    }

    public void setPayPenaltyType(String payPenaltyType) {
        this.payPenaltyType = payPenaltyType;
    }

    public Double getPayPenaltyValue() {
        return payPenaltyValue;
    }

    public void setPayPenaltyValue(Double payPenaltyValue) {
        this.payPenaltyValue = payPenaltyValue;
    }

    public Double getPayBonus() {
        return payBonus;
    }

    public void setPayBonus(Double payBonus) {
        this.payBonus = payBonus;
    }

    public Integer getAutosave1Hour() {
        return autosave1Hour;
    }

    public void setAutosave1Hour(Integer autosave1Hour) {
        this.autosave1Hour = autosave1Hour;
    }

    public Integer getAutosave1Minute() {
        return autosave1Minute;
    }

    public void setAutosave1Minute(Integer autosave1Minute) {
        this.autosave1Minute = autosave1Minute;
    }

    public String getAutosave1Meridiem() {
        return autosave1Meridiem;
    }

    public void setAutosave1Meridiem(String autosave1Meridiem) {
        this.autosave1Meridiem = autosave1Meridiem;
    }

    public Boolean getAutosave1Enabled() {
        return autosave1Enabled;
    }

    public void setAutosave1Enabled(Boolean autosave1Enabled) {
        this.autosave1Enabled = autosave1Enabled;
    }

    public Integer getAutosave2Hour() {
        return autosave2Hour;
    }

    public void setAutosave2Hour(Integer autosave2Hour) {
        this.autosave2Hour = autosave2Hour;
    }

    public Integer getAutosave2Minute() {
        return autosave2Minute;
    }

    public void setAutosave2Minute(Integer autosave2Minute) {
        this.autosave2Minute = autosave2Minute;
    }

    public String getAutosave2Meridiem() {
        return autosave2Meridiem;
    }

    public void setAutosave2Meridiem(String autosave2Meridiem) {
        this.autosave2Meridiem = autosave2Meridiem;
    }

    public Boolean getAutosave2Enabled() {
        return autosave2Enabled;
    }

    public void setAutosave2Enabled(Boolean autosave2Enabled) {
        this.autosave2Enabled = autosave2Enabled;
    }

    public List<ClassFee> getClasses() {
        return classes;
    }

    public void setClasses(List<ClassFee> classes) {
        this.classes = classes;
    }
}