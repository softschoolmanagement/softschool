package com.softschool.backend.repository;

import com.softschool.backend.model.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    
    // Find all staff (or students) marked for a specific day, scoped to one school.
    List<Attendance> findByMemberTypeAndDateAndSchoolId(String memberType, LocalDate date, String schoolId);

    // Same, but narrowed to one class too — used by the student roster view
    // so opening a single class doesn't have to pull every student in the school.
    List<Attendance> findByMemberTypeAndDateAndSchoolIdAndClassName(
            String memberType, LocalDate date, String schoolId, String className);

    // Every record (staff + students) for one day, scoped to a school — used
    // to build the main dashboard's "present today" summary.
    List<Attendance> findByDateAndSchoolId(LocalDate date, String schoolId);

    // To find a specific person on a specific day, within their own school.
    // Used both to look someone up and, on save, to decide whether a record
    // should be UPDATEd instead of INSERTed again (see AttendanceController#saveAttendance).
    Optional<Attendance> findByMemberIdAndDateAndSchoolId(String memberId, LocalDate date, String schoolId);

    // Full history for a person, scoped to their school (so a staffId/regNo
    // that happens to match one in another school never leaks in).
    List<Attendance> findByMemberIdAndSchoolIdOrderByDateDesc(String memberId, String schoolId);

    // Used by SuperAdminController#deleteSchool to permanently wipe every
    // attendance record for a school when the school itself is deleted.
    // NOT called on block/unblock — a blocked school's attendance history
    // is left intact.
    void deleteBySchoolId(String schoolId);
}