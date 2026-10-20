package com.softschool.backend.repository;

import com.softschool.backend.model.ParentReadMark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParentReadMarkRepository extends JpaRepository<ParentReadMark, Long> {
    Optional<ParentReadMark> findBySchoolIdAndPhoneAndThreadKey(String schoolId, String phone, String threadKey);

    List<ParentReadMark> findBySchoolIdAndPhone(String schoolId, String phone);

    long deleteBySchoolId(String schoolId);
}
