package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A parent's portal account. There is no separate "parents" table: a parent IS the phone number
 * written on one or more student records (Student.phone1 / phone2). One phone number = one account
 * = every child that carries that number (siblings share it).
 *
 * A row here only exists once the parent has CHANGED the default password. Until then the parent
 * signs in with the school-wide default parent password (see ParentPortalConfig).
 *
 * phone is stored as the LAST 10 DIGITS ("3001234567") so "0300-1234567" and "+92 300 1234567"
 * are the same account.
 */
@Entity
@Data
@Table(name = "parent_account",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "phone"}))
public class ParentAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 10)
    private String phone;

    /** Salted PBKDF2 hash (PasswordHashUtil). Null = still on the school's default password. */
    @Column(length = 255)
    private String passwordHash;

    private Instant passwordChangedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant lastLoginAt;
}
