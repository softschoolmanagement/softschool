package com.softschool.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

/** One row per school: the default password every parent signs in with until they change it. */
@Entity
@Data
@Table(name = "parent_portal_config",
       uniqueConstraints = @UniqueConstraint(columnNames = {"schoolId"}))
public class ParentPortalConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String schoolId;

    /** Salted hash. Never serialized. */
    @JsonIgnore
    @Column(length = 255)
    private String defaultPasswordHash;
}
