package com.softschool.backend.repository;

import com.softschool.backend.model.TeacherTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Tests of one class (matched by the class label or the bare class name). TeacherTestRepository is untouched. */
public interface ParentTestRepository extends Repository<TeacherTest, Long> {

    @Query("select t from TeacherTest t where t.schoolId = :schoolId " +
           "and (lower(t.className) = lower(:label) or lower(t.className) = lower(:cls)) " +
           "order by t.updatedAt desc")
    List<TeacherTest> findForClass(@Param("schoolId") String schoolId,
                                   @Param("label") String label,
                                   @Param("cls") String cls,
                                   Pageable pageable);
}
