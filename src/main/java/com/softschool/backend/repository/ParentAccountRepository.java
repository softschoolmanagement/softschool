package com.softschool.backend.repository;

import com.softschool.backend.model.ParentAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParentAccountRepository extends JpaRepository<ParentAccount, Long> {
    Optional<ParentAccount> findBySchoolIdAndPhone(String schoolId, String phone);

    // Used ONLY when a school is permanently deleted.
    long deleteBySchoolId(String schoolId);
}
