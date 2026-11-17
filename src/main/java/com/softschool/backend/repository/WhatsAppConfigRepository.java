package com.softschool.backend.repository;

import com.softschool.backend.model.WhatsAppConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WhatsAppConfigRepository extends JpaRepository<WhatsAppConfig, Long> {
    Optional<WhatsAppConfig> findBySchoolId(String schoolId);
}
