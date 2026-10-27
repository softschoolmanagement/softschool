package com.softschool.backend.service;

import com.softschool.backend.model.Student;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * JPA entity listener attached to {@link Student}. It fires for EVERY insert,
 * update and delete of a student row no matter which controller or service
 * caused it (StudentController, the finance page's bulk PUT, SuperAdmin's
 * prefix cascade, the class-fee change in SchoolSettingsController, ...), so
 * no individual write path has to remember to clear the cache.
 *
 * Hibernate instantiates this class itself, so it deliberately holds no
 * injected state and simply calls the static hook on StudentCacheService.
 */
public class StudentCacheInvalidationListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void studentChanged(Student student) {
        if (student != null) {
            StudentCacheService.studentChanged(student.getSchoolId(), student.getRegNo());
            // The parent portal resolves "which children belong to this phone" from student rows.
            DataCacheService.changed(DataCacheService.Dep.STUDENTS, student.getSchoolId());
        }
    }
}
