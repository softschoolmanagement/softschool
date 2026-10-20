package com.softschool.backend.repository;

import com.softschool.backend.dto.ParentTeacherDTO;
import com.softschool.backend.model.Staff;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Teachers of a school, light projection (no photo / agreement / salary). StaffRepository is untouched. */
public interface ParentStaffRepository extends Repository<Staff, Long> {

    @Query("select new com.softschool.backend.dto.ParentTeacherDTO(" +
           "s.staffId, s.name, s.subjects, s.classes, s.assignedClass, s.assignedSection, " +
           "s.classAssignments, s.inchargeAssignments, s.isClassIncharge, s.incharge) " +
           "from Staff s where s.schoolId = :schoolId and lower(s.type) = 'teaching'")
    List<ParentTeacherDTO> findTeachersBySchoolId(@Param("schoolId") String schoolId);
}
