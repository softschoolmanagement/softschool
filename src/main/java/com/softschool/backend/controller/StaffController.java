package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.dto.StaffSummaryDTO;
import com.softschool.backend.model.Attendance;
import com.softschool.backend.model.DropoutStaffRecord;
import com.softschool.backend.model.Finance;
import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.AttendanceRepository;
import com.softschool.backend.repository.DropoutStaffRecordRepository;
import com.softschool.backend.repository.FinanceRepository;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.service.PlanEnforcementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every endpoint here is scoped by schoolId (School.schoolId, e.g. "SS_77_1")
 * so each school only ever sees and modifies its own staff — see
 * Staff.schoolId / the (schoolId, staffId) unique constraint on the entity.
 * The frontend (manage-staff.js) always sends the logged-in school's ID,
 * taken from the real session set up at login.
 */
@RestController
@RequestMapping("/api/staff")
@CrossOrigin(origins = "*")
public class StaffController {

    @Autowired
    private StaffRepository staffRepository;

    @Autowired
    private PlanEnforcementService planEnforcementService;

    @Autowired
    private FinanceRepository financeRepository;

    @Autowired
    private DropoutStaffRecordRepository dropoutStaffRecordRepository;

    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private SchoolSettingsRepository schoolSettingsRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Staff staff) {
        if (isBlank(staff.getSchoolId())) {
            return badRequest("schoolId is required.");
        }
        if (isBlank(staff.getStaffId())) {
            return badRequest("staffId is required.");
        }

        // Handle Update logic: if staffId exists FOR THIS SCHOOL, reuse the
        // internal primary key so this is an update, not a duplicate insert —
        // scoped by schoolId too, so School A can never overwrite School B's
        // staff member just because they happen to share a staffId (e.g. same
        // prefix producing "PSC_S_1" for both).
        boolean isNew = staffRepository.findByStaffIdAndSchoolId(staff.getStaffId(), staff.getSchoolId())
                .map(existing -> {
                    staff.setId(existing.getId());
                    return false;
                })
                .orElse(true);

        // New hires count against staffLimit + the "staff" feature lock;
        // edits to an existing staff row don't change headcount, so they
        // only need the feature-lock check (Security Audit #2).
        if (isNew) {
            planEnforcementService.requireCapacityForNewStaff(staff.getSchoolId());
        } else {
            planEnforcementService.requireFeature(staff.getSchoolId(), PlanEnforcementService.FEATURE_STAFF);
        }

        Staff saved = staffRepository.save(staff);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<?> getAll(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        List<Staff> staffList = staffRepository.findBySchoolId(schoolId);
        applyAbsenceFines(staffList, schoolId);
        return ResponseEntity.ok(staffList);
    }

    /**
     * PERFORMANCE FIX — both the main dashboard's Staff attendance card
     * (same main.js code path as StudentController#getStudentsSummary
     * above) AND the Attendance page's staff roster cards (attendance.js's
     * loadRealStaff()) used to call plain GET /api/staff, which drags
     * every staff member's base64 photo/agreementData/classAssignments/
     * inchargeAssignments LONGTEXT blob along with it, even though neither
     * caller ever reads those fields — main.js only needs staffId + salary
     * (to derive headcount + absence fines), and attendance.js only needs
     * staffId/type/name/role/subjects/job (to render the Teaching /
     * Non-Teaching roster cards).
     *
     * This lightweight endpoint (see StaffSummaryDTO / the JPQL projection
     * in StaffRepository) selects only those columns at the SQL level.
     * The derived `fines` / `absentDaysThisMonth` figures are then filled
     * in the same way as the full-entity endpoint above, just onto the
     * lighter DTO — see applyAbsenceFinesToSummary. Full staff records
     * (with photos) are still served as before by GET /api/staff, for
     * Manage Staff.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(@RequestParam(required = false) String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        List<StaffSummaryDTO> summaryList = staffRepository.findSummaryBySchoolId(schoolId);
        applyAbsenceFinesToSummary(summaryList, schoolId);
        return ResponseEntity.ok(summaryList);
    }

    /**
     * BUGFIX — "staff absent fine set from Settings never updates in
     * Salaries or the Dashboard": nothing ever actually computed it. The
     * Leave Penalty pay variable (Settings > Global Pay Variables ->
     * SchoolSettings.payPenaltyType/payPenaltyValue, saved by
     * SchoolSettingsController#saveAll) was always persisted correctly, and
     * `Staff.fines` was always the field manage-finance.js's salary
     * pages/panel and main.js's dashboard read (as `t.fines` /
     * `member.fines`) — but the two were never wired together. The
     * frontend's old client-side calculator (settings.js#computeAbsenceFine)
     * was intentionally disabled the moment attendance moved server-side
     * (see attendance.js#applyAbsenceFines: "Absence fines application
     * handled by backend."), yet no backend replacement was ever written,
     * so `fines` just silently stayed 0 forever, no matter what Leave
     * Penalty was configured or how many days someone was actually marked
     * absent.
     *
     * This fills `fines` (and the new `absentDaysThisMonth`, so the UI can
     * show its work) in live, on every staff fetch, straight from real
     * Attendance rows for the CURRENT calendar month and the school's Leave
     * Penalty setting — "% per day" of that staff member's salary, or a
     * flat Rs/day. Deliberately never persisted back to the DB: it's a
     * derived, always-fresh "right now" figure, the same way
     * main.js/_dashboardStaffFineTotals and manage-finance.js's
     * getEffectiveSalaryDuePreview() already treat absence fines — a value
     * that's only ever meaningful for the current month, not a historical
     * fact to store.
     */
    private void applyAbsenceFines(List<Staff> staffList, String schoolId) {
        if (staffList.isEmpty()) return;
        AbsenceFineContext ctx = buildAbsenceFineContext(schoolId);
        for (Staff staff : staffList) {
            int absentDays = ctx.absentDaysByStaffId.getOrDefault(staff.getStaffId(), 0);
            staff.setAbsentDaysThisMonth(absentDays);
            staff.setFines(computeFine(ctx, staff.getSalary(), absentDays));
        }
    }

    /**
     * Same absence-fine derivation as applyAbsenceFines above, applied onto
     * the lightweight StaffSummaryDTO used by the dashboard's Staff card
     * (GET /api/staff/summary) instead of the full Staff entity — kept as a
     * separate method (rather than a generic one) because Staff and
     * StaffSummaryDTO don't share a common setter interface.
     */
    private void applyAbsenceFinesToSummary(List<StaffSummaryDTO> staffList, String schoolId) {
        if (staffList.isEmpty()) return;
        AbsenceFineContext ctx = buildAbsenceFineContext(schoolId);
        for (StaffSummaryDTO staff : staffList) {
            int absentDays = ctx.absentDaysByStaffId.getOrDefault(staff.getStaffId(), 0);
            staff.setAbsentDaysThisMonth(absentDays);
            staff.setFines(computeFine(ctx, staff.getSalary(), absentDays));
        }
    }

    /**
     * Everything about absence fines that's the SAME for every staff member
     * in a school this month (which staffIds were marked absent on which
     * days, and what the school's Leave Penalty rule currently is) — computed
     * once per request and reused for however many staff rows need it,
     * instead of re-querying attendance/settings per row.
     */
    private AbsenceFineContext buildAbsenceFineContext(String schoolId) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        List<Attendance> monthRows = attendanceRepository.findByMemberTypeAndSchoolIdAndDateBetween(
                "STAFF", schoolId, monthStart, monthEnd);

        Map<String, Integer> absentDaysByStaffId = new HashMap<>();
        for (Attendance row : monthRows) {
            if ("absent".equalsIgnoreCase(row.getStatus())) {
                absentDaysByStaffId.merge(row.getMemberId(), 1, Integer::sum);
            }
        }

        SchoolSettings settings = schoolSettingsRepository.findBySchoolId(schoolId).orElse(null);
        String penaltyType = (settings != null && settings.getPayPenaltyType() != null)
                ? settings.getPayPenaltyType()
                : "percent";
        double penaltyValue = (settings != null && settings.getPayPenaltyValue() != null)
                ? settings.getPayPenaltyValue()
                : 3.0;

        AbsenceFineContext ctx = new AbsenceFineContext();
        ctx.absentDaysByStaffId = absentDaysByStaffId;
        ctx.penaltyType = penaltyType;
        ctx.penaltyValue = penaltyValue;
        return ctx;
    }

    private double computeFine(AbsenceFineContext ctx, double salary, int absentDays) {
        if (absentDays <= 0) return 0;
        double fine = "percent".equalsIgnoreCase(ctx.penaltyType)
                ? (salary * (ctx.penaltyValue / 100.0)) * absentDays
                : ctx.penaltyValue * absentDays;
        return Math.round(fine);
    }

    private static class AbsenceFineContext {
        Map<String, Integer> absentDaysByStaffId;
        String penaltyType;
        double penaltyValue;
    }

    @DeleteMapping("/{staffId}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable String staffId, @RequestParam String schoolId) {
        if (isBlank(schoolId)) {
            return badRequest("schoolId query parameter is required.");
        }
        return staffRepository.findByStaffIdAndSchoolId(staffId, schoolId)
                .map(existing -> {
                    // BUGFIX — "deleted staff's paid salary/fines/advance
                    // stop showing up anywhere on the dashboard": every
                    // Finance row for this staffId is about to be wiped
                    // below (see the next comment for why), so capture a
                    // permanent snapshot of what was actually paid to this
                    // staff member FIRST — before any of that history is
                    // gone — and archive it as a DropoutStaffRecord. The
                    // dashboard's "Dropout Staff Paid Salary" card (GET
                    // /api/finance/dropout-staff) reads these archives back,
                    // so money paid to now-deleted staff keeps counting
                    // toward Net Expenses forever after, instead of
                    // disappearing the moment the staff row is removed.
                    archiveDropoutStaffSnapshot(existing, staffId, schoolId);

                    staffRepository.deleteById(existing.getId());

                    // BUGFIX — "delete a staff member, add a new one, and the
                    // new hire's salary shows Paid" / "add another after that
                    // and it flips to Pending":
                    //
                    // manage-staff.js's generateStaffId() always reuses the
                    // lowest free numeric suffix (e.g. delete staff #3, the
                    // next hire becomes #3 again), but this endpoint used to
                    // only delete the Staff row — every Finance row (SALARY,
                    // ADVANCE, and staff bonus/fine entries) stayed behind,
                    // still keyed by that same staffId. So the next hire who
                    // got id #3 reused would immediately "inherit" whatever
                    // salary/advance/bonus/fine history the deleted staff #3
                    // had (e.g. an already-Paid salary row for the current
                    // month), while the hire after that got a fresh, never
                    // reused id and correctly showed Pending. Wiping every
                    // finance record tied to this staffId here means a
                    // reused id always starts with a clean slate.
                    financeRepository.deleteByStaffIdAndSchoolId(staffId, schoolId);
                    purgeStaffFromBulkFinanceLists(staffId, schoolId);

                    return ResponseEntity.ok().build();
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Sums up everything this staff member was ever actually paid — every
     * SALARY row's amountPaid (all months), every still-outstanding ADVANCE
     * (paymentStatus != "Settled" — a settled advance is already folded
     * into a SALARY row's amountPaid, so counting it here too would double
     * it, mirroring how the live dashboard treats advances in main.js's
     * _dashboardAdvanceTotal), and every STAFF_BONUS entry on file for
     * them — plus their STAFF_FINE total, kept for record-keeping only
     * (a fine is a deduction, not a payout, so it's never added into
     * `total`). Only archives a record when there's actually something to
     * remember, so deleting a staff member with zero finance history never
     * clutters the dropout-staff list with a $0 row.
     */
    private void archiveDropoutStaffSnapshot(com.softschool.backend.model.Staff staff, String staffId, String schoolId) {
        double paidSalaryTotal = financeRepository
                .findByStaffIdAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(staffId, Finance.TYPE_SALARY, schoolId)
                .stream()
                .mapToDouble(row -> {
                    if (row.getAmountPaid() != null) return row.getAmountPaid();
                    double netPaid = row.getNetPaid() != null ? row.getNetPaid() : 0.0;
                    double advanceDeducted = row.getAdvanceDeducted() != null ? row.getAdvanceDeducted() : 0.0;
                    return netPaid + advanceDeducted;
                })
                .sum();

        double advancePaidTotal = financeRepository
                .findByStaffIdAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(staffId, Finance.TYPE_ADVANCE, schoolId)
                .stream()
                .filter(row -> !"Settled".equalsIgnoreCase(row.getPaymentStatus()))
                .mapToDouble(row -> row.getAmount() != null ? row.getAmount() : 0.0)
                .sum();

        double bonusPaidTotal = sumBulkFinanceAmountForStaff(Finance.TYPE_STAFF_BONUS, staffId, schoolId);
        double finesTotal = sumBulkFinanceAmountForStaff(Finance.TYPE_STAFF_FINE, staffId, schoolId);

        double total = paidSalaryTotal + advancePaidTotal + bonusPaidTotal;
        if (total <= 0.0 && finesTotal <= 0.0) {
            return;
        }

        DropoutStaffRecord record = new DropoutStaffRecord();
        record.setSchoolId(schoolId);
        record.setStaffId(staffId);
        record.setStaffName(staff.getName());
        record.setPaidSalaryTotal(paidSalaryTotal);
        record.setAdvancePaidTotal(advancePaidTotal);
        record.setBonusPaidTotal(bonusPaidTotal);
        record.setFinesTotal(finesTotal);
        record.setTotal(total);
        dropoutStaffRecordRepository.save(record);
    }

    /**
     * Sums the "amount" field embedded in payloadJson for every row of a
     * bulk-list recordType (STAFF_BONUS / STAFF_FINE) that belongs to this
     * staff member — same lookup rowBelongsToStaff() below already does for
     * purging, reused here to total them up before they're gone.
     */
    private double sumBulkFinanceAmountForStaff(String recordType, String staffId, String schoolId) {
        return financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(recordType, schoolId)
                .stream()
                .filter(row -> rowBelongsToStaff(row, staffId))
                .mapToDouble(row -> {
                    try {
                        JsonNode node = objectMapper.readTree(row.getPayloadJson());
                        return node.hasNonNull("amount") ? node.get("amount").asDouble() : 0.0;
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .sum();
    }

    /**
     * STAFF_BONUS / STAFF_FINE / STAFF_ADVANCE_BULK rows (see Finance.java)
     * don't carry a real staffId column — each row's staffId lives inside
     * its payloadJson blob instead (as "staffId", falling back to "id").
     * financeRepository.deleteByStaffIdAndSchoolId() can't reach those, so
     * walk each bucket here and drop any row whose embedded staffId/id
     * matches the staff member that was just deleted — otherwise a reused
     * staffId could still surface a dead staff member's old bonus/fine
     * entries once it's assigned to a new hire.
     */
    private void purgeStaffFromBulkFinanceLists(String staffId, String schoolId) {
        List<String> bulkTypes = List.of(
                Finance.TYPE_STAFF_BONUS,
                Finance.TYPE_STAFF_FINE,
                Finance.TYPE_STAFF_ADVANCE_BULK
        );
        for (String recordType : bulkTypes) {
            List<Finance> rows = financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(recordType, schoolId);
            for (Finance row : rows) {
                if (rowBelongsToStaff(row, staffId)) {
                    financeRepository.delete(row);
                }
            }
        }
    }

    private boolean rowBelongsToStaff(Finance row, String staffId) {
        String payload = row.getPayloadJson();
        if (isBlank(payload)) return false;
        try {
            JsonNode node = objectMapper.readTree(payload);
            String embeddedStaffId = node.hasNonNull("staffId") ? node.get("staffId").asText()
                    : node.hasNonNull("id") ? node.get("id").asText() : null;
            return staffId.equals(embeddedStaffId);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        return Collections.singletonMap("error", message);
    }
}