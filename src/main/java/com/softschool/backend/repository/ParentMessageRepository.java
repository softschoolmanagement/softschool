package com.softschool.backend.repository;

import com.softschool.backend.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Extra Message queries for the parent portal. MessageRepository is untouched. */
public interface ParentMessageRepository extends Repository<Message, Long> {

    /** One child's private chats: parent_admin + parent_teacher. */
    List<Message> findBySchoolIdAndStudentRegNoAndChannelInOrderByCreatedAtDesc(
            String schoolId, String studentRegNo, Collection<String> channels, Pageable pageable);

    /** A teacher's broadcasts to every parent of a class. */
    List<Message> findBySchoolIdAndChannelAndClassNameIgnoreCaseOrderByCreatedAtDesc(
            String schoolId, String channel, String className, Pageable pageable);

    /** Spam guard for parent sends. */
    long countBySchoolIdAndStudentRegNoAndSenderTypeAndCreatedAtAfter(
            String schoolId, String studentRegNo, String senderType, Instant after);
}
