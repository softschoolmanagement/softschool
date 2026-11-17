package com.softschool.backend.repository;

import com.softschool.backend.model.WhatsAppLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppLogRepository extends JpaRepository<WhatsAppLog, Long> {
    boolean existsBySchoolIdAndDedupeKey(String schoolId, String dedupeKey);
}
