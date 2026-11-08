package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * What the Super Admin has already looked at, one row per portal ("parent" / "teacher").
 * Feedback with an id above lastSeenId is "new" (red circle + green row in the Feedback page).
 * Ids only ever grow, so a single number per portal is enough.
 */
@Entity
@Data
@Table(name = "feedback_seen")
public class FeedbackSeen {

    @Id
    @Column(length = 20)
    private String source;

    @Column(nullable = false)
    private Long lastSeenId = 0L;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}
