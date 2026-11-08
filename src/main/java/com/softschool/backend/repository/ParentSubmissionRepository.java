package com.softschool.backend.repository;

import com.softschool.backend.model.ParentSubmission;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ParentSubmissionRepository extends JpaRepository<ParentSubmission, Long> {
    List<ParentSubmission> findBySchoolIdAndPhoneOrderByCreatedAtDesc(String schoolId, String phone, Pageable pageable);

    List<ParentSubmission> findBySchoolIdOrderByCreatedAtDesc(String schoolId, Pageable pageable);

    List<ParentSubmission> findBySchoolIdAndKindOrderByCreatedAtDesc(String schoolId, String kind, Pageable pageable);

    // Super Admin: every school's submissions of one kind (e.g. "feedback" = feedback about the software).
    List<ParentSubmission> findByKindOrderByCreatedAtDesc(String kind, Pageable pageable);

    long countByKindAndIdGreaterThan(String kind, Long id);

    Optional<ParentSubmission> findByIdAndSchoolId(Long id, String schoolId);

    long countBySchoolIdAndPhoneAndCreatedAtAfter(String schoolId, String phone, Instant after);

    long deleteBySchoolId(String schoolId);
}
