package com.softschool.backend.repository;


import com.softschool.backend.dto.StudentSummaryDTO;
import com.softschool.backend.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByRegNo(String regNo);
    List<Student> findByStatus(String status);

    // ── school-scoped lookups (each school only ever sees its own data) ──
    List<Student> findBySchoolId(String schoolId);
    List<Student> findBySchoolIdAndStatus(String schoolId, String status);
    Optional<Student> findByRegNoAndSchoolId(String regNo, String schoolId);

    // PERFORMANCE FIX (slow Student dashboard card AND slow Attendance page
    // roster cards) — see StudentSummaryDTO for why this exists: a JPQL
    // constructor expression that selects only the plain columns both
    // main.js's dashboard and attendance.js's roster cards actually read,
    // so the photo/certData/otherFeesData LONGTEXT columns are never read
    // or sent over the wire just to compute a headcount, an attendance
    // percentage, or render a student's name/class/section/guardian.
    @Query("select new com.softschool.backend.dto.StudentSummaryDTO(" +
            "s.regNo, s.status, s.admissionDate, s.admissionFee, " +
            "s.fullName, s.studentClass, s.section, s.guardianName) " +
            "from Student s where s.schoolId = :schoolId")
    List<StudentSummaryDTO> findSummaryBySchoolId(@Param("schoolId") String schoolId);

    // Used ONLY when a school is permanently deleted by the super admin —
    // wipes every student row belonging to that schoolId. Deliberately NOT
    // called from the block/unblock endpoint; blocking only flips
    // School.status and must never touch this table.
    long deleteBySchoolId(String schoolId);

    // Live enrolled-student count for a school, used by the super admin
    // panel to show "X / studentLimit" usage and trigger the near-limit alert.
    long countBySchoolId(String schoolId);
}