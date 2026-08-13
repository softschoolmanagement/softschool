package com.softschool.backend.repository;

import com.softschool.backend.model.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StaffRepository extends JpaRepository<Staff, Long> {
    List<Staff> findBySchoolId(String schoolId);
    Optional<Staff> findByStaffIdAndSchoolId(String staffId, String schoolId);
    void deleteBySchoolId(String schoolId);

    // Live staff count for a school, used by the super admin panel to show
    // "X / staffLimit" usage and trigger the near-limit alert.
    long countBySchoolId(String schoolId);
}