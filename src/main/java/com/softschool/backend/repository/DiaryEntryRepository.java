package com.softschool.backend.repository;

import com.softschool.backend.model.DiaryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DiaryEntryRepository extends JpaRepository<DiaryEntry, Long> {

    List<DiaryEntry> findBySchoolIdAndStaffIdOrderByCreatedAtDesc(String schoolId, String staffId, Pageable pageable);

    List<DiaryEntry> findBySchoolIdOrderByCreatedAtDesc(String schoolId, Pageable pageable);

    /** What the parent portal will show: published entries of one class. */
    List<DiaryEntry> findBySchoolIdAndClassNameAndPublishedTrueOrderByCreatedAtDesc(String schoolId, String className, Pageable pageable);

    /** PERFORMANCE - parent /pulse: only the newest published entry id of one class (null when none). */
    @org.springframework.data.jpa.repository.Query(
        "select max(d.id) from DiaryEntry d where d.schoolId = :schoolId and d.className = :className and d.published = true")
    Long findNewestPublishedId(@org.springframework.data.repository.query.Param("schoolId") String schoolId,
                               @org.springframework.data.repository.query.Param("className") String className);

    Optional<DiaryEntry> findByIdAndSchoolId(Long id, String schoolId);

    long countBySchoolIdAndStaffIdAndCreatedAtAfter(String schoolId, String staffId, Instant after);

    // Used ONLY when a school is permanently deleted, and when a staff member is removed.
    long deleteBySchoolId(String schoolId);
    long deleteBySchoolIdAndStaffId(String schoolId, String staffId);
}
