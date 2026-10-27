package com.softschool.backend.repository;

import com.softschool.backend.model.ExamGrading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ExamGradingRepository extends JpaRepository<ExamGrading, Long> {

    Optional<ExamGrading> findBySchoolId(String schoolId);

    @Transactional
    long deleteBySchoolId(String schoolId);
}
