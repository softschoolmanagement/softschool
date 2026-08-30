package com.softschool.backend.repository;

import com.softschool.backend.dto.StaffSummaryDTO;
import com.softschool.backend.model.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffRepository extends JpaRepository<Staff, Long> {
    List<Staff> findBySchoolId(String schoolId);
    Optional<Staff> findByStaffIdAndSchoolId(String staffId, String schoolId);
    void deleteBySchoolId(String schoolId);

    // Live staff count for a school, used by the super admin panel to show
    // "X / staffLimit" usage and trigger the near-limit alert.
    long countBySchoolId(String schoolId);

    // PERFORMANCE FIX (slow Staff dashboard card AND slow Attendance page
    // roster cards) — see StaffSummaryDTO: selects only the plain columns
    // main.js's dashboard and attendance.js's roster cards actually read,
    // so photo/agreementData/classAssignments/inchargeAssignments LONGTEXT
    // columns are never read or sent over the wire just to compute a
    // headcount + fines total, or render a staff member's name/role.
    @Query("select new com.softschool.backend.dto.StaffSummaryDTO(" +
            "s.staffId, s.salary, s.type, s.name, s.role, s.subjects, s.job) " +
            "from Staff s where s.schoolId = :schoolId")
    List<StaffSummaryDTO> findSummaryBySchoolId(@Param("schoolId") String schoolId);
}