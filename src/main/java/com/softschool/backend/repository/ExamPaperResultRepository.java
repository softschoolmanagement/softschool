package com.softschool.backend.repository;

import com.softschool.backend.model.ExamPaperResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ExamPaperResultRepository extends JpaRepository<ExamPaperResult, Long> {

    List<ExamPaperResult> findBySchoolId(String schoolId);

    Optional<ExamPaperResult> findBySchoolIdAndPaperId(String schoolId, String paperId);

    @Transactional
    long deleteBySchoolIdAndPaperId(String schoolId, String paperId);

    // Used ONLY when a school is permanently deleted by the super admin.
    @Transactional
    long deleteBySchoolId(String schoolId);
}
