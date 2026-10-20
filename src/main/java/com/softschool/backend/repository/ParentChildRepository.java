package com.softschool.backend.repository;

import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.dto.ParentSignupDTO;
import com.softschool.backend.model.Student;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Finds the children a parent phone number belongs to. Matching is done on the last 10 digits with
 * the usual formatting characters stripped, so "0300-1234567", "0300 1234567" and "+92 300 1234567"
 * all find the same students. The caller (ParentChildrenService) re-checks every hit in Java.
 *
 * Only ACTIVE students (status null or 'active') belong to a parent portal: graduated / dropped
 * students are archived and their parents lose access.
 */
public interface ParentChildRepository extends Repository<Student, Long> {

    String SELECT =
            "select new com.softschool.backend.dto.ParentChildDTO(" +
            "s.schoolId, s.regNo, s.fullName, s.rollNo, s.studentClass, s.section, s.gender, s.status, " +
            "s.guardianName, s.guardianRole, s.fatherFullName, s.phone1, s.phone2, s.permanentAddress, " +
            "case when s.photo is not null and length(s.photo) > 0 then true else false end) " +
            "from Student s where (s.status is null or lower(s.status) = 'active') and ";

    String PHONE1 = "replace(replace(replace(replace(coalesce(s.phone1,''),'-',''),' ',''),'(',''),')','')";
    String PHONE2 = "replace(replace(replace(replace(coalesce(s.phone2,''),'-',''),' ',''),'(',''),')','')";

    /** Across every school (used only by the login, which does not know the school yet). */
    @Query(SELECT + "(" + PHONE1 + " like :pat or " + PHONE2 + " like :pat)")
    List<ParentChildDTO> findActiveByPhone(@Param("pat") String pat);

    /** One school (every request after login). */
    @Query(SELECT + "s.schoolId = :schoolId and (" + PHONE1 + " like :pat or " + PHONE2 + " like :pat)")
    List<ParentChildDTO> findActiveByPhoneInSchool(@Param("schoolId") String schoolId, @Param("pat") String pat);

    /** Children attached at sign-up (see ParentLinkedChild), by registration number inside one school. */
    @Query(SELECT + "s.schoolId = :schoolId and s.regNo in :regNos")
    List<ParentChildDTO> findActiveByRegNosInSchool(@Param("schoolId") String schoolId, @Param("regNos") Collection<String> regNos);

    // ── sign-up ───────────────────────────────────────────────────

    /** Every active student with this registration number (regNo is only unique WITHIN a school). */
    @Query("select new com.softschool.backend.dto.ParentSignupDTO(s.schoolId, s.regNo, s.fatherCnic, s.guardianCnic, " +
           "s.guardianRole, s.phone1, s.phone2, s.siblingGroupId) from Student s " +
           "where s.regNo = :regNo and (s.status is null or lower(s.status) = 'active')")
    List<ParentSignupDTO> findActiveSignupByRegNo(@Param("regNo") String regNo);

    /** Active students of the same school-recorded sibling group. */
    @Query("select s.regNo from Student s where s.schoolId = :schoolId and s.siblingGroupId = :groupId " +
           "and (s.status is null or lower(s.status) = 'active')")
    List<String> findActiveRegNosBySiblingGroup(@Param("schoolId") String schoolId, @Param("groupId") String groupId);

    /** The JSON [{name, regNo}, ...] the school's "Mark as Sibling" flow stores on the student. */
    @Query("select s.hasSiblings from Student s where s.schoolId = :schoolId and s.regNo = :regNo")
    List<String> findHasSiblingsJson(@Param("schoolId") String schoolId, @Param("regNo") String regNo);
}
