package com.softschool.backend.repository;

import com.softschool.backend.model.Announcement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** What a teacher sees: all-teacher notices + notices aimed at her, never her own sent messages. */
    @Query("select a from Announcement a where a.schoolId = :schoolId and " +
           "(a.audience = 'teachers' or (a.audience = 'teacher' and a.targetStaffId = :staffId)) and " +
           "not (a.senderType = 'TEACHER' and a.senderId = :staffId) " +
           "order by a.createdAt desc")
    List<Announcement> findVisibleToTeacher(@Param("schoolId") String schoolId,
                                            @Param("staffId") String staffId,
                                            Pageable pageable);

    /** Admin view: everything in the school, newest first. */
    List<Announcement> findBySchoolIdOrderByCreatedAtDesc(String schoolId, Pageable pageable);

    /** Admin inbox: messages teachers sent to the admin. */
    List<Announcement> findBySchoolIdAndAudienceOrderByCreatedAtDesc(String schoolId, String audience, Pageable pageable);

    Optional<Announcement> findByIdAndSchoolId(Long id, String schoolId);

    /** Spam guard for teacher sends. */
    long countBySchoolIdAndSenderTypeAndSenderIdAndCreatedAtAfter(
            String schoolId, String senderType, String senderId, Instant after);

    // Used ONLY when a school is permanently deleted by the super admin.
    long deleteBySchoolId(String schoolId);
}
