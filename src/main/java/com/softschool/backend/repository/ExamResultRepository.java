package com.softschool.backend.repository;

import com.softschool.backend.model.ExamResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    List<ExamResult> findBySchoolIdAndRegNo(String schoolId, String regNo);

    /** What a parent may see: only results the admin has sent. */
    List<ExamResult> findBySchoolIdAndRegNoAndVisibleTrue(String schoolId, String regNo);

    /** PERFORMANCE - parent /pulse: how many results the admin has sent for this child. */
    long countBySchoolIdAndRegNoAndVisibleTrue(String schoolId, String regNo);

    /** Admin: the send / hold status of a whole session + term. */
    List<ExamResult> findBySchoolIdAndSessionAndTerm(String schoolId, String session, String term);

    Optional<ExamResult> findBySchoolIdAndRegNoAndSessionAndTerm(String schoolId, String regNo, String session, String term);

    long deleteBySchoolId(String schoolId);
}
