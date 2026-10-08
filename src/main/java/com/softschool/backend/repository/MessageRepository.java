package com.softschool.backend.repository;

import com.softschool.backend.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySchoolIdAndStaffIdOrderByCreatedAtDesc(String schoolId, String staffId, Pageable pageable);

    List<Message> findBySchoolIdOrderByCreatedAtDesc(String schoolId, Pageable pageable);

    Optional<Message> findBySchoolIdAndStaffIdAndClientId(String schoolId, String staffId, String clientId);

    long countBySchoolIdAndStaffIdAndSenderTypeAndCreatedAtAfter(String schoolId, String staffId, String senderType, Instant after);

    /** Teacher opened the admin chat: everything the admin wrote is now read. */
    @Modifying
    @Query("update Message m set m.readByTeacher = true where m.schoolId = :schoolId and m.staffId = :staffId " +
           "and m.channel = 'admin' and m.senderType <> 'TEACHER' and m.readByTeacher = false")
    int markAdminChannelReadByTeacher(@Param("schoolId") String schoolId, @Param("staffId") String staffId);

    /** Teacher opened a class (parents) thread. */
    @Modifying
    @Query("update Message m set m.readByTeacher = true where m.schoolId = :schoolId and m.staffId = :staffId " +
           "and m.channel = 'class' and m.className = :className and m.senderType <> 'TEACHER' and m.readByTeacher = false")
    int markClassChannelReadByTeacher(@Param("schoolId") String schoolId, @Param("staffId") String staffId,
                                      @Param("className") String className);

    /** Admin opened a teacher's chat: everything the teacher wrote is now read. */
    @Modifying
    @Query("update Message m set m.readByAdmin = true where m.schoolId = :schoolId and m.staffId = :staffId " +
           "and m.channel = 'admin' and m.senderType = 'TEACHER' and m.readByAdmin = false")
    int markAdminChannelReadByAdmin(@Param("schoolId") String schoolId, @Param("staffId") String staffId);

    // Used ONLY when a school is permanently deleted, and when a staff member is removed.
    long deleteBySchoolId(String schoolId);
    long deleteBySchoolIdAndStaffId(String schoolId, String staffId);
}
