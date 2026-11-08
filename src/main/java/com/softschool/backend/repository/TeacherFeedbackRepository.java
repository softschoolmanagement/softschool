package com.softschool.backend.repository;

import com.softschool.backend.model.TeacherFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TeacherFeedbackRepository extends JpaRepository<TeacherFeedback, Long> {
    List<TeacherFeedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<TeacherFeedback> findByTypeOrderByCreatedAtDesc(String type, Pageable pageable);

    long countBySchoolIdAndStaffIdAndCreatedAtAfter(String schoolId, String staffId, Instant after);

    long deleteBySchoolId(String schoolId);
}
