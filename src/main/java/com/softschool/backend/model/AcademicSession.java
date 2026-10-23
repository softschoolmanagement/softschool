package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * A school-defined academic/session date range.
 *
 * Month values use the ISO "yyyy-MM" format so session boundaries remain
 * timezone-independent and can be used directly when querying monthly fees.
 */
@Entity
@Table(
    name = "academic_sessions",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"schoolId", "startMonth", "endMonth"}
    )
)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AcademicSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String schoolId;

    @Column(nullable = false, length = 7)
    private String startMonth;

    @Column(nullable = false, length = 7)
    private String endMonth;

    @Column(nullable = false)
    private boolean firstSession;

    /**
     * True for the one session the school has chosen as its current session.
     * At most one session per school has this set (enforced by the controller).
     * The default keeps rows that existed before this column was added valid.
     */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean currentSession;
}
