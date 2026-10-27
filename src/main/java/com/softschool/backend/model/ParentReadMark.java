package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * "This parent has read this conversation up to readAt." Read state has to be per parent (not a flag
 * on the message) because a class broadcast is ONE row seen by every parent of the class.
 *
 * threadKey = "<regNo>|admin" or "<regNo>|t:<staffId>"  (one per child per conversation).
 */
@Entity
@Data
@EntityListeners(com.softschool.backend.service.DataCacheInvalidationListener.class)
@Table(name = "parent_read_mark",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "phone", "threadKey"}))
public class ParentReadMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 10)
    private String phone;

    @Column(nullable = false, length = 140)
    private String threadKey;

    @Column(nullable = false)
    private Instant readAt = Instant.now();
}
