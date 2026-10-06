package com.softschool.backend.repository;

import com.softschool.backend.model.TeacherTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherTestRepository extends JpaRepository<TeacherTest, Long> {

    List<TeacherTest> findBySchoolIdAndStaffIdOrderByUpdatedAtDesc(String schoolId, String staffId);

    List<TeacherTest> findBySchoolIdOrderByUpdatedAtDesc(String schoolId);

    Optional<TeacherTest> findBySchoolIdAndStaffIdAndTestId(String schoolId, String staffId, String testId);

    long countBySchoolIdAndStaffId(String schoolId, String staffId);

    long deleteBySchoolIdAndStaffIdAndTestId(String schoolId, String staffId, String testId);

    // Used ONLY when a school is permanently deleted, and when a staff member is removed.
    long deleteBySchoolId(String schoolId);
    long deleteBySchoolIdAndStaffId(String schoolId, String staffId);
}
