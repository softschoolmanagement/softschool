package com.softschool.backend.repository;

import com.softschool.backend.model.ExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, Long> {

    /** Newest first, matching how the Examination screens list papers. */
    List<ExamPaper> findBySchoolIdOrderByCreatedAtDescIdDesc(String schoolId);

    Optional<ExamPaper> findBySchoolIdAndPaperId(String schoolId, String paperId);

    long countBySchoolId(String schoolId);

    @Transactional
    long deleteBySchoolIdAndPaperId(String schoolId, String paperId);

    // Used ONLY when a school is permanently deleted by the super admin.
    @Transactional
    long deleteBySchoolId(String schoolId);
}
