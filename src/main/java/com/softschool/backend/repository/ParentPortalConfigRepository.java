package com.softschool.backend.repository;

import com.softschool.backend.model.ParentPortalConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParentPortalConfigRepository extends JpaRepository<ParentPortalConfig, Long> {
    Optional<ParentPortalConfig> findBySchoolId(String schoolId);

    long deleteBySchoolId(String schoolId);
}
