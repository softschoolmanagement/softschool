package com.softschool.backend.repository;

import com.softschool.backend.model.ExamDateSheetEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ExamDateSheetRepository extends JpaRepository<ExamDateSheetEntry, Long> {

    List<ExamDateSheetEntry> findBySchoolIdOrderByExamDateAscStartTimeAscIdAsc(String schoolId);

    Optional<ExamDateSheetEntry> findBySchoolIdAndEntryId(String schoolId, String entryId);

    long countBySchoolId(String schoolId);

    @Transactional
    long deleteBySchoolIdAndEntryId(String schoolId, String entryId);

    // Used ONLY when a school is permanently deleted by the super admin.
    @Transactional
    long deleteBySchoolId(String schoolId);
}
