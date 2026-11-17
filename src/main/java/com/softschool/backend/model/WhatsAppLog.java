package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/** Audit + de-duplication: one row per WhatsApp message handed to the gateway. */
@Entity
@Data
@Table(name = "whatsapp_log",
       uniqueConstraints = @UniqueConstraint(name = "uk_wa_log_dedupe", columnNames = {"schoolId", "dedupeKey"}),
       indexes = @Index(name = "idx_wa_log_school_time", columnList = "schoolId,createdAt"))
public class WhatsAppLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String schoolId;
    private String dedupeKey;   // e.g. abs:2026-11-16:REG001  (a re-saved attendance sheet never re-sends)
    private String kind;        // ABSENT | FEE | BROADCAST | TEST
    private String regNo;
    private String phone;
    private Instant createdAt = Instant.now();
}
