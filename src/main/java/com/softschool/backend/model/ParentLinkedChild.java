package com.softschool.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A child attached to a parent account at SIGN-UP, beyond the children that share the parent's phone
 * number: the signed-up student plus every sibling the school's own records link to that student
 * (Student.siblingGroupId / hasSiblings). The portal shows (children by phone) UNION (these rows).
 */
@Entity
@Data
@EntityListeners(com.softschool.backend.service.DataCacheInvalidationListener.class)
@Table(name = "parent_linked_child",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId", "phone", "regNo"}))
public class ParentLinkedChild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    @Column(nullable = false, length = 10)
    private String phone;

    @Column(nullable = false, length = 64)
    private String regNo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
