package com.softschool.backend.repository;

import com.softschool.backend.model.ParentLinkedChild;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ParentLinkedChildRepository extends JpaRepository<ParentLinkedChild, Long> {
    List<ParentLinkedChild> findBySchoolIdAndPhone(String schoolId, String phone);

    boolean existsBySchoolIdAndPhoneAndRegNo(String schoolId, String phone, String regNo);

    long deleteBySchoolId(String schoolId);
}
