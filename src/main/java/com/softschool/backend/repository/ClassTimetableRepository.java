package com.softschool.backend.repository;

import com.softschool.backend.model.ClassTimetable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassTimetableRepository extends JpaRepository<ClassTimetable, Long> {
    Optional<ClassTimetable> findBySchoolIdAndClassNameIgnoreCase(String schoolId, String className);

    long deleteBySchoolId(String schoolId);
}
