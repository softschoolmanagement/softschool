package com.softschool.backend.repository;

import com.softschool.backend.model.DropoutStaffRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * One row per deleted staff member — see DropoutStaffRecord's class docs.
 * Every finder is scoped by schoolId, same pattern as every other
 * repository in this app (StaffRepository, FinanceRepository, ...).
 */
public interface DropoutStaffRecordRepository extends JpaRepository<DropoutStaffRecord, Long> {

    List<DropoutStaffRecord> findBySchoolIdOrderByDeletedAtDesc(String schoolId);

    long deleteBySchoolId(String schoolId);
}