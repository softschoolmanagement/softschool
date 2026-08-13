package com.softschool.backend.repository;

import com.softschool.backend.model.SchoolSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for SchoolSettings — one row per school, keyed by School.schoolId
 * (e.g. "SS_77_12"), NOT by the SchoolSettings.id primary key.
 */
@Repository
public interface SchoolSettingsRepository extends JpaRepository<SchoolSettings, Long> {

    Optional<SchoolSettings> findBySchoolId(String schoolId);

    boolean existsBySchoolId(String schoolId);

    // Used by SuperAdminController#deleteSchool to permanently wipe a
    // school's settings row when the school itself is deleted.
    void deleteBySchoolId(String schoolId);
}