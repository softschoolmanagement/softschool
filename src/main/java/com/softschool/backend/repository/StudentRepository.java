package com.softschool.backend.repository;


import com.softschool.backend.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByRegNo(String regNo);
    List<Student> findByStatus(String status);

    // ── school-scoped lookups (each school only ever sees its own data) ──
    List<Student> findBySchoolId(String schoolId);
    List<Student> findBySchoolIdAndStatus(String schoolId, String status);
    Optional<Student> findByRegNoAndSchoolId(String regNo, String schoolId);

    // Used ONLY when a school is permanently deleted by the super admin —
    // wipes every student row belonging to that schoolId. Deliberately NOT
    // called from the block/unblock endpoint; blocking only flips
    // School.status and must never touch this table.
    long deleteBySchoolId(String schoolId);

    // Live enrolled-student count for a school, used by the super admin
    // panel to show "X / studentLimit" usage and trigger the near-limit alert.
    long countBySchoolId(String schoolId);
}