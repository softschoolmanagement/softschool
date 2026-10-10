package com.softschool.backend.repository;

import com.softschool.backend.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findBySchoolIdAndStaffIdOrderByCreatedAtDesc(String schoolId, String staffId, Pageable pageable);

    List<Message> findBySchoolIdOrderByCreatedAtDesc(String schoolId, Pageable pageable);

    List<Message> findBySchoolIdAndStaffIdAndChannelOrderByCreatedAtDesc(String schoolId, String staffId, String channel, Pageable pageable);

    List<Message> findBySchoolIdAndChannelAndStudentRegNoOrderByCreatedAtDesc(String schoolId, String channel, String studentRegNo, Pageable pageable);

    /** Everything the admin can see: the "admin" and "parent_admin" channels. */
    List<Message> findBySchoolIdAndChannelInOrderByCreatedAtDesc(String schoolId, Collection<String> channels, Pageable pageable);

    /** Cheap "did anything change?" probe for the admin's live view. */
    @Query("select coalesce(max(m.id), 0) from Message m where m.schoolId = :schoolId and m.channel in ('admin', 'parent_admin')")
    long maxAdminVisibleId(@Param("schoolId") String schoolId);

    @Query("select count(m) from Message m where m.schoolId = :schoolId and m.channel in ('admin', 'parent_admin') " +
           "and m.senderType <> 'ADMIN' and m.readByAdmin = false")
    long unreadForAdmin(@Param("schoolId") String schoolId);

    /** Pulse extras so the live view also notices edits and deletions. */
    @Query("select count(m) from Message m where m.schoolId = :schoolId and m.channel in ('admin', 'parent_admin')")
    long countAdminVisible(@Param("schoolId") String schoolId);

    @Query("select max(m.editedAt) from Message m where m.schoolId = :schoolId and m.channel in ('admin', 'parent_admin')")
    Instant maxAdminEditedAt(@Param("schoolId") String schoolId);

    Optional<Message> findByIdAndSchoolId(Long id, String schoolId);

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

    /** Teacher opened the chat with one student's parent. */
    @Modifying
    @Query("update Message m set m.readByTeacher = true where m.schoolId = :schoolId and m.staffId = :staffId " +
           "and m.channel = 'parent_teacher' and m.studentRegNo = :regNo and m.senderType <> 'TEACHER' and m.readByTeacher = false")
    int markParentTeacherReadByTeacher(@Param("schoolId") String schoolId, @Param("staffId") String staffId,
                                       @Param("regNo") String regNo);

    /** Admin opened the chat with one student's parent. */
    @Modifying
    @Query("update Message m set m.readByAdmin = true where m.schoolId = :schoolId and m.channel = 'parent_admin' " +
           "and m.studentRegNo = :regNo and m.senderType = 'PARENT' and m.readByAdmin = false")
    int markParentAdminReadByAdmin(@Param("schoolId") String schoolId, @Param("regNo") String regNo);

    /** Admin opened a teacher's chat: everything the teacher wrote is now read. */
    @Modifying
    @Query("update Message m set m.readByAdmin = true where m.schoolId = :schoolId and m.staffId = :staffId " +
           "and m.channel = 'admin' and m.senderType = 'TEACHER' and m.readByAdmin = false")
    int markAdminChannelReadByAdmin(@Param("schoolId") String schoolId, @Param("staffId") String staffId);

    // Used ONLY when a school is permanently deleted, and when a staff member is removed.
    long deleteBySchoolId(String schoolId);
    long deleteBySchoolIdAndStaffId(String schoolId, String staffId);
}
