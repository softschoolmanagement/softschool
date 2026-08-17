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

    // Every STAFF attendance row for one school within a date range, in a
    // single query — used by StaffController#applyAbsenceFines to count
    // this month's absent days for every staff member at once instead of
    // querying per-person.
    List<Attendance> findByMemberTypeAndSchoolIdAndDateBetween(
            String memberType, String schoolId, LocalDate start, LocalDate end);

    // EVERY attendance row (every member, every date) for one school —
    // used by Reports & Analytics to build the 12-month Attendance Trend
    // chart / Avg Attendance figure, which needs the whole year's raw marks
    // at once rather than one day at a time. See AttendanceController's
    // GET /all for why this had to be added.
    List<Attendance> findBySchoolId(String schoolId);

    // Used by SuperAdminController#deleteSchool to permanently wipe every
    // attendance record for a school when the school itself is deleted.
    // NOT called on block/unblock — a blocked school's attendance history
    // is left intact.
    void deleteBySchoolId(String schoolId);
}