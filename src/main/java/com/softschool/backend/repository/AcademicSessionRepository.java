package com.softschool.backend.repository;

import com.softschool.backend.model.AcademicSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicSessionRepository extends JpaRepository<AcademicSession, Long> {

    List<AcademicSession> findBySchoolIdOrderByStartMonthAsc(String schoolId);

    Optional<AcademicSession> findBySchoolIdAndStartMonthAndEndMonth(
            String schoolId,
            String startMonth,
            String endMonth);

    /** Looks a session up by id, but only inside the given school. */
    Optional<AcademicSession> findByIdAndSchoolId(Long id, String schoolId);

    Optional<AcademicSession> findFirstBySchoolIdAndCurrentSessionTrue(String schoolId);
}
