package com.softschool.backend.repository;

import com.softschool.backend.model.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    List<ExamResult> findBySchoolIdAndRegNo(String schoolId, String regNo);

    Optional<ExamResult> findBySchoolIdAndRegNoAndSessionAndTerm(String schoolId, String regNo, String session, String term);

    long deleteBySchoolId(String schoolId);
}
