package com.softschool.backend.service;

import com.softschool.backend.model.Announcement;
import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.ClassTimetable;
import com.softschool.backend.model.DiaryEntry;
import com.softschool.backend.model.ExamResult;
import com.softschool.backend.model.Finance;
import com.softschool.backend.model.Message;
import com.softschool.backend.model.ParentAccount;
import com.softschool.backend.model.ParentLinkedChild;
import com.softschool.backend.model.ParentReadMark;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Staff;
import com.softschool.backend.model.TeacherTest;
import com.softschool.backend.service.DataCacheService.Dep;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * JPA entity listener shared by every entity that {@link DataCacheService}
 * caches reads of. It fires for EVERY insert, update and delete of such a row,
 * whichever controller or service caused it, so no write path has to remember
 * to clear a cache. (Student rows are handled by StudentCacheInvalidationListener.)
 *
 * Hibernate instantiates this class itself, so it holds no injected state.
 */
public class DataCacheInvalidationListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        if (entity instanceof Attendance) {
            DataCacheService.changed(Dep.ATTENDANCE, ((Attendance) entity).getSchoolId());
        } else if (entity instanceof Finance) {
            DataCacheService.changed(Dep.FINANCE, ((Finance) entity).getSchoolId());
        } else if (entity instanceof Message) {
            DataCacheService.changed(Dep.MESSAGES, ((Message) entity).getSchoolId());
        } else if (entity instanceof ParentReadMark) {
            DataCacheService.changed(Dep.READMARKS, ((ParentReadMark) entity).getSchoolId());
        } else if (entity instanceof Announcement) {
            DataCacheService.changed(Dep.ANNOUNCEMENTS, ((Announcement) entity).getSchoolId());
        } else if (entity instanceof DiaryEntry) {
            DataCacheService.changed(Dep.DIARY, ((DiaryEntry) entity).getSchoolId());
        } else if (entity instanceof TeacherTest) {
            DataCacheService.changed(Dep.TESTS, ((TeacherTest) entity).getSchoolId());
        } else if (entity instanceof ClassTimetable) {
            DataCacheService.changed(Dep.TIMETABLE, ((ClassTimetable) entity).getSchoolId());
        } else if (entity instanceof ExamResult) {
            DataCacheService.changed(Dep.RESULTS, ((ExamResult) entity).getSchoolId());
        } else if (entity instanceof Staff) {
            DataCacheService.changed(Dep.STAFF, ((Staff) entity).getSchoolId());
        } else if (entity instanceof SchoolSettings) {
            DataCacheService.changed(Dep.SETTINGS, ((SchoolSettings) entity).getSchoolId());
        } else if (entity instanceof ParentAccount) {
            DataCacheService.changed(Dep.ACCOUNTS, ((ParentAccount) entity).getSchoolId());
        } else if (entity instanceof ParentLinkedChild) {
            DataCacheService.changed(Dep.LINKS, ((ParentLinkedChild) entity).getSchoolId());
        }
    }
}
