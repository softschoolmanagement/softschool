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
    public static final String DEFAULT_VOUCHER =
            "Assalam o Alaikum. Fee voucher from {school}: {student} (Class {class}) for {month}. "
          + "Total amount: Rs. {amount}. Payable: Rs. {balance}. Last date to pay: {duedate}. Thank you.";
    public static final String DEFAULT_REMINDER =
            "Assalam o Alaikum. Fee reminder from {school}: the fee of {student} (Class {class}) for {month} "
          + "was due on {duedate} and Rs. {balance} is still pending. Please pay at your earliest. "
          + "If you have already paid, kindly ignore this message.";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String schoolId;

    private Boolean enabled = false;          // master switch
    private Boolean notifyAbsent = true;      // auto message when a student is marked absent
    private Boolean notifyVoucher = false;    // message the parent when a fee voucher is generated
    private Boolean notifyReminder = false;   // after the fee deadline, remind ONLY parents whose fee is still pending
    private Integer dailyLimit;               // this school's own max messages/day (null = server default); can only lower the gateway ceiling

    @Column(length = 1000) private String absentTemplate = DEFAULT_ABSENT;
    @Column(length = 1000) private String voucherTemplate = DEFAULT_VOUCHER;
    @Column(length = 1000) private String reminderTemplate = DEFAULT_REMINDER;

    public boolean isOn()           { return Boolean.TRUE.equals(enabled); }
    public boolean wantsAbsent()    { return isOn() && !Boolean.FALSE.equals(notifyAbsent); }
    public boolean wantsVoucher()   { return isOn() && Boolean.TRUE.equals(notifyVoucher); }
    public boolean wantsReminder()  { return isOn() && Boolean.TRUE.equals(notifyReminder); }
}
