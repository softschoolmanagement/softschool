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
}