package com.softschool.backend.repository;

import com.softschool.backend.model.School;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SchoolRepository extends JpaRepository<School, Long> {

    boolean existsBySchoolId(String schoolId);

    List<School> findByPlanId(String planId);

    // ── used by the public school login/registration endpoints ──
    Optional<School> findBySchoolId(String schoolId);

    Optional<School> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Looks at every existing schoolId matching "<prefix>_<number>" (e.g. SS_77_12)
     * and returns the highest number used so far (0 if none exist yet).
     * The controller adds 1 to this to mint the next schoolId.
     * MySQL-specific (SUBSTRING_INDEX / REGEXP) — matches the LONGTEXT usage
     * elsewhere in the entity, which implies a MySQL database.
     */
    @Query(value =
        "SELECT COALESCE(MAX(CAST(SUBSTRING_INDEX(school_id, '_', -1) AS UNSIGNED)), 0) " +
        "FROM schools " +
        "WHERE school_id REGEXP CONCAT('^', :prefix, '_[0-9]+$')",
        nativeQuery = true)
    Integer findMaxSchoolIdSuffix(@Param("prefix") String prefix);
}