package com.softschool.backend.repository;

import com.softschool.backend.model.Finance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ONE repository for ALL finance records (student fee ledgers, individual
 * fines, salary payments, salary advances) — see Finance.java's class docs
 * for what each recordType means. Replaces StudentFinanceRepository,
 * FineRecordRepository, and SalaryRepository.
 *
 * Every finder is scoped by schoolId (and recordType, where a table used to
 * be dedicated to a single type) so callers in FinanceController never have
 * to remember to filter school-in-code — it's baked into the query.
 */
public interface FinanceRepository extends JpaRepository<Finance, Long> {

    // ---- STUDENT_FEE: one master ledger row per (schoolId, regNo, monthKey) ----
    Optional<Finance> findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
            String regNo, String monthKey, String recordType, String schoolId);

    // ---- STUDENT_FEE: every student's ledger row for a given month (used for "all fines this month") ----
    List<Finance> findByMonthKeyAndRecordTypeAndSchoolId(
            String monthKey, String recordType, String schoolId);

    // ---- FINE: every individual fine event for a student in a given month, newest first ----
    List<Finance> findByRegNoAndMonthKeyAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(
            String regNo, String monthKey, String recordType, String schoolId);

    // ---- SALARY: has this staff member already been paid for this month? ----
    Optional<Finance> findByStaffIdAndMonthKeyAndRecordTypeAndSchoolId(
            String staffId, String monthKey, String recordType, String schoolId);

    // ---- ADVANCE: outstanding (not-yet-settled) advances for a staff member in a given month ----
    List<Finance> findByStaffIdAndMonthKeyAndRecordTypeAndSchoolIdAndPaymentStatus(
            String staffId, String monthKey, String recordType, String schoolId, String paymentStatus);

    // ---- SALARY/ADVANCE history: every payroll record for a staff member, newest first ----
    List<Finance> findByStaffIdAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(
            String staffId, String recordType, String schoolId);

    // ---- Generic "bulk list" support: every row of a given recordType for a
    // school (EXPENSE, CUSTOM_FEE, STAFF_BONUS, STAFF_FINE, VOUCHER,
    // STAFF_ADVANCE_BULK — see Finance.java's payloadJson docs), oldest first
    // so the frontend sees items in the order they were originally added ----
    List<Finance> findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(String recordType, String schoolId);

    // ---- Used by the bulk-list PUT endpoints to implement "replace the
    // whole list": delete every existing row of this recordType for this
    // school before re-inserting the frontend's new array ----
    long deleteByRecordTypeAndSchoolId(String recordType, String schoolId);

    // Used ONLY when a school is permanently deleted by the super admin —
    // wipes every finance record (fee ledgers, fines, salaries, advances)
    // belonging to that schoolId. Deliberately NOT called from block/unblock.
    long deleteBySchoolId(String schoolId);
}