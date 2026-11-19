package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "plans")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Plan {

    @Id
    private String id; // e.g., "standard", "pro_2"

    private String label;
    private Integer price;
    private Integer studentLimit;

    // Max number of staff members (teaching + non-teaching) this plan allows.
    private Integer staffLimit;

    // Stored as comma-separated keys, e.g., "biometric,finance"
    @Column(columnDefinition = "TEXT")
    private String locks;

    // Super-admin switch: when true, EVERY school on this plan is banned from the WhatsApp
    // messaging feature (hidden in Settings and refused by the API). Checked live on every request,
    // so it also covers schools whose own `locks` override differs from the plan's.
    // null (never set / older rows) is treated as false.
    private Boolean whatsappBanned;
}