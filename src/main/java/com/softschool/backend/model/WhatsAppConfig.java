package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

/** Per-school WhatsApp automation switches + message templates (one row per school). */
@Entity
@Data
@Table(name = "whatsapp_config", uniqueConstraints = @UniqueConstraint(name = "uk_wa_cfg_school", columnNames = "schoolId"))
public class WhatsAppConfig {
    public static final String DEFAULT_ABSENT =
            "Assalam o Alaikum. Attendance alert from {school}: {student} (Class {class}) was marked ABSENT today ({date}). "
          + "If this is a mistake or your child is on approved leave, please contact the school office.";
    public static final String DEFAULT_FEE =
            "Fee received by {school}: Rs. {amount} for {month} for {student} (Class {class}). "
          + "Remaining balance: Rs. {balance}. Thank you.";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String schoolId;

    private Boolean enabled = false;          // master switch
    private Boolean notifyAbsent = true;      // auto message when a student is marked absent
    private Boolean notifyFeePaid = false;    // auto receipt when a fee payment is recorded

    @Column(length = 1000) private String absentTemplate = DEFAULT_ABSENT;
    @Column(length = 1000) private String feeTemplate = DEFAULT_FEE;

    public boolean isOn()        { return Boolean.TRUE.equals(enabled); }
    public boolean wantsAbsent() { return isOn() && !Boolean.FALSE.equals(notifyAbsent); }
    public boolean wantsFee()    { return isOn() && Boolean.TRUE.equals(notifyFeePaid); }
}
