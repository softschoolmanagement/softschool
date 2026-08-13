package com.softschool.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softschool.backend.model.Finance;
import com.softschool.backend.model.Staff;
import com.softschool.backend.model.Student;
import com.softschool.backend.repository.FinanceRepository;
import com.softschool.backend.repository.StaffRepository;
import com.softschool.backend.repository.StudentRepository;
import com.softschool.backend.service.PlanEnforcementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ALL finance logic — student fee ledgers, individual student fines, staff
 * salary payments, and staff salary advances — lives in this one
 * controller now, backed by the single Finance entity/FinanceRepository.
 * Replaces the old FinanceController + SalaryController (which in turn
 * used to be split across StudentFinance/FineRecord/SalaryRecord and 3
 * repositories).
 *
 * SCHOOL SCOPING: every endpoint requires schoolId (School.schoolId, e.g.
 * "SS_77_1") — as a query param on GETs, in the JSON body on POSTs — and
 * every lookup/save goes through it, exactly like StaffController scopes
 * Staff by schoolId. This is what lets two schools each have a student or
 * staff member with the same regNo/staffId without ever seeing or
 * affecting each other's fee ledgers, fines, salaries, or advances.
 */
@RestController
@RequestMapping("/api/finance")
@CrossOrigin(origins = "*")
public class FinanceController {

    @Autowired private StudentRepository studentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private FinanceRepository financeRepository;
    @Autowired private PlanEnforcementService planEnforcementService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // =========================================================
    // 1. STUDENT FEE STATUS — get or initialize (with roll-over)
    // =========================================================
    @GetMapping("/status/{regNoOrId}/{monthKey}")
    public ResponseEntity<?> getStudentFinance(@PathVariable String regNoOrId,
                                                @PathVariable String monthKey,
                                                @RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        Finance f = getOrCreateStudentFeeMaster(regNoOrId, monthKey, schoolId);
        if (f == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("Student not found."));
        }
        // Reconcile older rows where the monthly bill was already paid with
        // the fine included, but the individual FINE row or the master's
        // running fineAmount was left pending by an earlier payment flow.
        settleCoveredFineRecords(f, schoolId);
        return ResponseEntity.ok(f);
    }

    /**
     * Finds this student's STUDENT_FEE master row for the month, or creates
     * it (rolling over the previous month's remaining balance as arrears)
     * exactly like GET /status used to do inline. Used by /status itself,
     * and also by /add-fine and /pay below so a fine or a payment never
     * fails just because nobody happened to open this student's fee page
     * first — the record is initialized on first touch either way.
     * Returns null only if regNoOrId doesn't resolve to a student in this school.
     */
    private Finance getOrCreateStudentFeeMaster(String regNoOrId, String monthKey, String schoolId) {
        // Resolve the current roster record first.  Do not return an old
        // finance row for a student who has since been archived.
        Student student = findStudentInSchool(regNoOrId, schoolId);
        if (student == null) {
            return null;
        }

        Optional<Finance> existing = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                regNoOrId, monthKey, Finance.TYPE_STUDENT_FEE, schoolId);
        if (existing.isPresent()) {
            return existing.get();
        }

        double previousArrears = 0.0;
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM");
            LocalDate currentMonthDate = LocalDate.parse(monthKey + "-01");
            String prevMonthKey = currentMonthDate.minusMonths(1).format(formatter);
            Optional<Finance> prevFinance = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                    regNoOrId, prevMonthKey, Finance.TYPE_STUDENT_FEE, schoolId);
            if (prevFinance.isPresent()) {
                previousArrears = nz(prevFinance.get().getRemainingBalance());
            }
        } catch (Exception e) {
            // malformed monthKey — just skip roll-over
        }

        Finance f = new Finance();
        f.setSchoolId(schoolId);
        f.setRecordType(Finance.TYPE_STUDENT_FEE);
        f.setRegNo(student.getRegNo());
        f.setStudentName(student.getFullName());
        f.setStudentClass(student.getStudentClass());
        f.setSection(student.getSection());
        f.setGuardianName(student.getGuardianName());
        f.setBaseTuitionFee(student.getStandardFee());
        f.setTransportFee(student.getTransportFee());
        f.setOtherCharges(previousArrears); // roll-over arrears
        f.setMonthKey(monthKey);

        // BUGFIX — "Dashboard Expected/Pending doesn't match Manage Finance":
        // this row's netPayable used to be computed purely from gross fee +
        // arrears + fine, with totalDiscountApplied left at 0 — the
        // student's profile discounts (tuitionDiscount/transportDiscount/
        // siblingDiscount) were only ever applied client-side in Manage
        // Finance's computeFeeBreakdown(), never persisted here. Seed the
        // ledger with them now so /status-all's netPayable (which the
        // Dashboard sums for Expected/Pending Fees) matches the discounted
        // total Manage Finance already shows.
        double profileDiscount = nz(student.getTuitionDiscount())
                + nz(student.getTransportDiscount())
                + nz(student.getSiblingDiscount());
        f.setTotalDiscountApplied(profileDiscount);

        f.calculateNetPayable();
        return financeRepository.save(f);
    }

    // =========================================================
    // 2. ALL STUDENTS WITH FINES FOR A MONTH
    // =========================================================
    @GetMapping("/all-fines/{monthKey}")
    public ResponseEntity<?> getAllFines(@PathVariable String monthKey, @RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");

        List<Finance> allRecords = financeRepository.findByMonthKeyAndRecordTypeAndSchoolId(
                monthKey, Finance.TYPE_STUDENT_FEE, schoolId);
        List<Finance> withFines = allRecords.stream()
                .filter(f -> isActiveStudentInSchool(f.getRegNo(), schoolId))
                .filter(f -> f.getFineAmount() != null && f.getFineAmount() > 0)
                .collect(Collectors.toList());
        return ResponseEntity.ok(withFines);
    }

    // =========================================================
    // 2b. ALL STUDENT FEE STATUS FOR A MONTH (bulk — used by the frontend's
    //     school-wide/class-wide "Collected"/"Pending" totals so they read
    //     the persisted backend ledger instead of a browser-only cache
    //     that gets wiped on every page refresh)
    // =========================================================
    @GetMapping("/status-all/{monthKey}")
    public ResponseEntity<?> getAllStudentFeeStatus(@PathVariable String monthKey, @RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        List<Finance> records = financeRepository.findByMonthKeyAndRecordTypeAndSchoolId(
                monthKey, Finance.TYPE_STUDENT_FEE, schoolId);
        return ResponseEntity.ok(records.stream()
                .filter(record -> isActiveStudentInSchool(record.getRegNo(), schoolId))
                .collect(Collectors.toList()));
    }

    // =========================================================
    // 3. ADD FINE (to a student)
    // =========================================================
    @PostMapping("/add-fine")
    public ResponseEntity<?> addFine(@RequestBody Map<String, Object> payload) {
        String schoolId = str(payload, "schoolId");
        String regNo = str(payload, "regNo");
        String monthKey = str(payload, "monthKey");
        String reason = str(payload, "reason");
        Double amount = doubleOrNull(payload, "amount");

        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);
        if (isBlank(regNo) || isBlank(monthKey) || amount == null) {
            return badRequest("regNo, monthKey and amount are required.");
        }

        Finance currentMaster = getOrCreateStudentFeeMaster(regNo, monthKey, schoolId);
        if (currentMaster == null) {
            return badRequest("Student not found.");
        }

        /*
         * A fee that has already been fully paid must stay paid.  Putting a
         * newly-added fine back onto that same month's master ledger changes
         * the ledger from Paid to Partial/Pending and makes the already-paid
         * voucher show "Pay Bill" again.  Carry the fine to the next billing
         * month instead.  If this month's fee is not paid, the fine belongs
         * to this month and is included in its voucher as before.
         */
        boolean currentFeePaid = "Paid".equalsIgnoreCase(currentMaster.getPaymentStatus())
                || (currentMaster.getRemainingBalance() != null
                    && currentMaster.getRemainingBalance() <= 0.01);
        String fineMonthKey = currentFeePaid ? nextMonthKey(monthKey) : monthKey;
        Finance master = currentMaster;
        if (!fineMonthKey.equals(monthKey)) {
            master = getOrCreateStudentFeeMaster(regNo, fineMonthKey, schoolId);
            if (master == null) {
                return badRequest("Student not found.");
            }
        }

        master.setFineAmount(nz(master.getFineAmount()) + amount);
        master.setFineReason(isBlank(master.getFineReason()) ? reason : master.getFineReason() + ", " + reason);
        master.calculateNetPayable();
        financeRepository.save(master);

        Finance fine = new Finance();
        fine.setSchoolId(schoolId);
        fine.setRecordType(Finance.TYPE_FINE);
        fine.setRegNo(master.getRegNo());
        fine.setStudentName(master.getStudentName());
        fine.setStudentClass(master.getStudentClass());
        fine.setSection(master.getSection());
        fine.setGuardianName(master.getGuardianName());
        fine.setMonthKey(fineMonthKey);
        fine.setAmount(amount);
        fine.setReason(reason);
        fine.setPaymentStatus("Pending");
        fine.stampApplyNow();
        financeRepository.save(fine);

        return ResponseEntity.ok(master);
    }

    // =========================================================
    // 4. GENERAL FEE PAYMENT (auto-settles individual fines)
    // =========================================================
    @PostMapping("/pay")
    @Transactional
    public ResponseEntity<?> processPayment(@RequestBody Map<String, Object> payload) {
        String schoolId = str(payload, "schoolId");
        String regNo = str(payload, "regNo");
        String monthKey = str(payload, "monthKey");
        Double amount = doubleOrNull(payload, "amount");
        // BUGFIX — "on-the-spot discount reverts to Pending after refresh":
        // the frontend lets an admin settle a bill using a mix of cash paid
        // + a discount, but this endpoint only ever accounted for `amount`.
        // The discount was only ever recorded in the browser's local cache
        // (as a fake "payment" of method:'discount'), never on the backend
        // ledger, so remainingBalance here never dropped for it — meaning
        // any refresh (which now reads paidAmount/remainingBalance straight
        // from this table) would show the discounted portion as still owed.
        double discount = doubleOr(payload, "discount", 0);

        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);
        if (isBlank(regNo) || isBlank(monthKey) || amount == null) {
            return badRequest("regNo, monthKey and amount are required.");
        }

        Finance master = getOrCreateStudentFeeMaster(regNo, monthKey, schoolId);
        if (master == null) {
            return badRequest("Student not found.");
        }

        master.setPaidAmount(nz(master.getPaidAmount()) + amount);
        if (discount > 0) {
            master.setTotalDiscountApplied(nz(master.getTotalDiscountApplied()) + discount);
        }
        master.calculateNetPayable();

        // Auto-settle individual fines covered by this payment and remove
        // them from the monthly master's running fine total as well.
        settleCoveredFineRecords(master, schoolId);

        return ResponseEntity.ok(financeRepository.save(master));
    }

    // =========================================================
    // 5. INDIVIDUAL FINE SETTLEMENT (removes reason from voucher)
    // =========================================================
    @PostMapping("/pay-fine/{id}")
    public ResponseEntity<?> payIndividualFine(@PathVariable Long id,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        String schoolId = payload != null ? str(payload, "schoolId") : null;
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);

        Optional<Finance> fineOpt = financeRepository.findById(id);
        if (fineOpt.isEmpty()) return ResponseEntity.notFound().build();

        Finance fine = fineOpt.get();
        if (!Finance.TYPE_FINE.equals(fine.getRecordType()) || !schoolId.equals(fine.getSchoolId())) {
            // Either not a fine row, or it belongs to a different school — treat as not found
            return ResponseEntity.notFound().build();
        }
        if (isFinePaid(fine)) {
            return badRequest("Already paid");
        }

        markFinePaid(fine);

        Optional<Finance> masterOpt = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolId(
                fine.getRegNo(), fine.getMonthKey(), Finance.TYPE_STUDENT_FEE, schoolId);
        if (masterOpt.isPresent()) {
            Finance master = masterOpt.get();
            removeFineFromMaster(master, fine);
            master.calculateNetPayable();
            financeRepository.save(master);
        }

        return ResponseEntity.ok(fine);
    }

    private void markFinePaid(Finance fr) {
        fr.setPaymentStatus("Paid");
        fr.stampPayNow();
        financeRepository.save(fr);
    }

    private boolean isFinePaid(Finance fine) {
        return fine != null
                && ("Paid".equalsIgnoreCase(fine.getPaymentStatus())
                    || "Settled".equalsIgnoreCase(fine.getPaymentStatus()));
    }

    /**
     * Settles individual fine rows when the student's monthly paid amount
     * covers the fee plus those fines. This keeps the typed master ledger and
     * the individual FINE rows consistent. It is called both after a payment
     * and while reading status, so old inconsistent rows repair themselves on
     * the next fee-table refresh.
     */
    private boolean settleCoveredFineRecords(Finance master, String schoolId) {
        if (master == null
                || !Finance.TYPE_STUDENT_FEE.equals(master.getRecordType())
                || nz(master.getFineAmount()) <= 0.01) {
            return false;
        }

        double feesWithoutFines = Math.max(0.0,
                nz(master.getNetPayable()) - nz(master.getFineAmount()));
        double coveredForFines = nz(master.getPaidAmount()) - feesWithoutFines;
        if (coveredForFines <= 0.01) {
            return false;
        }

        List<Finance> allFines = financeRepository
                .findByRegNoAndMonthKeyAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(
                        master.getRegNo(),
                        master.getMonthKey(),
                        Finance.TYPE_FINE,
                        schoolId)
                ;
        if (allFines.isEmpty()) {
            return false;
        }

        boolean changed = false;
        double unpaidFineTotal = 0.0;
        List<String> unpaidFineReasons = new ArrayList<>();

        for (Finance fine : allFines) {
            boolean finePaid = isFinePaid(fine);
            double fineAmount = nz(fine.getAmount());

            if (!finePaid && coveredForFines + 0.01 >= fineAmount) {
                markFinePaid(fine);
                finePaid = true;
                coveredForFines -= fineAmount;
                changed = true;
            }

            if (!finePaid) {
                unpaidFineTotal += fineAmount;
                if (!isBlank(fine.getReason())) {
                    unpaidFineReasons.add(fine.getReason());
                }
            }
        }

        String rebuiltReason = unpaidFineReasons.isEmpty()
                ? null
                : String.join(", ", unpaidFineReasons);
        if (Math.abs(nz(master.getFineAmount()) - unpaidFineTotal) > 0.01
                || !Objects.equals(master.getFineReason(), rebuiltReason)) {
            master.setFineAmount(unpaidFineTotal);
            master.setFineReason(rebuiltReason);
            changed = true;
        }

        if (changed) {
            master.calculateNetPayable();
            financeRepository.save(master);
        }
        return changed;
    }

    private void removeFineFromMaster(Finance master, Finance fine) {
        master.setFineAmount(Math.max(0, nz(master.getFineAmount()) - nz(fine.getAmount())));
        if (master.getFineReason() != null && fine.getReason() != null) {
            String updated = master.getFineReason().replace(fine.getReason(), "")
                    .replaceAll(",\\s*,", ",")
                    .replaceAll("^,\\s*|\\s*,\\s*$", "");
            master.setFineReason(updated.isEmpty() ? null : updated);
        }
    }

    // =========================================================
    // 6. FINE DETAILS (full history for a student's month)
    // =========================================================
    @GetMapping("/fine-details/{regNo}/{monthKey}")
    public ResponseEntity<?> getFineDetails(@PathVariable String regNo,
                                             @PathVariable String monthKey,
                                             @RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        List<Finance> fines = financeRepository.findByRegNoAndMonthKeyAndRecordTypeAndSchoolIdOrderByCreatedAtDesc(
                regNo, monthKey, Finance.TYPE_FINE, schoolId);
        if (!isActiveStudentInSchool(regNo, schoolId)) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(fines);
    }

    // =========================================================
    // 7. SALARY PAYMENT (settles any pending advances + security deposit)
    // =========================================================
    @PostMapping("/salary/pay")
    public ResponseEntity<?> paySalary(@RequestBody Map<String, Object> payload) {
        try {
            String schoolId = str(payload, "schoolId");
            String staffId = str(payload, "staffId");
            String monthKey = str(payload, "monthKey");
            double bonus = doubleOr(payload, "bonus", 0);
            double fine = doubleOr(payload, "fine", 0);

            if (isBlank(schoolId)) return badRequest("schoolId is required.");
            planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);
            if (isBlank(staffId) || isBlank(monthKey)) return badRequest("staffId and monthKey are required.");

            Staff staff = staffRepository.findByStaffIdAndSchoolId(staffId, schoolId).orElse(null);
            if (staff == null) return badRequest("Staff not found.");

            Optional<Finance> existingSalary =
                    financeRepository.findByStaffIdAndMonthKeyAndRecordTypeAndSchoolId(
                            staffId, monthKey, Finance.TYPE_SALARY, schoolId);
            if (existingSalary.isPresent()) {
                return badRequest("Salary for this month has already been paid.");
            }

            double baseSalary = staff.getSalary();

            // Settle any outstanding advances for this staff member this month
            List<Finance> pendingAdvances = financeRepository.findByStaffIdAndMonthKeyAndRecordTypeAndSchoolIdAndPaymentStatus(
                    staffId, monthKey, Finance.TYPE_ADVANCE, schoolId, "Advance");
            double advanceDeducted = 0.0;
            for (Finance adv : pendingAdvances) {
                advanceDeducted += nz(adv.getAmount());
                adv.setPaymentStatus("Settled");
                financeRepository.save(adv);
            }

            // Collect this month's security deposit installment, if any is still owed
            double securityDeducted = 0.0;
            double securityTotal = staff.getSecurityTotal();
            double securityCollected = staff.getSecurityCollected();
            if (securityTotal > 0 && securityCollected < securityTotal) {
                securityDeducted = Math.min(staff.getSecurityMonthly(), securityTotal - securityCollected);
                staff.setSecurityCollected(securityCollected + securityDeducted);
                staffRepository.save(staff);
            }

            double netPaid = baseSalary + bonus - fine - advanceDeducted - securityDeducted;

            Finance record = new Finance();
            record.setSchoolId(schoolId);
            record.setRecordType(Finance.TYPE_SALARY);
            record.setStaffId(staffId);
            record.setMonthKey(monthKey);
            record.setBaseSalary(baseSalary);
            record.setBonus(bonus);
            record.setFines(fine);
            record.setSecurityDeducted(securityDeducted);
            record.setAdvanceDeducted(advanceDeducted);
            record.setNetPaid(netPaid);
            record.setPaymentDate(LocalDateTime.now());
            record.setPaymentStatus("Paid");

            return ResponseEntity.ok(financeRepository.save(record));
        } catch (Exception e) {
            return badRequest(e.getMessage());
        }
    }

    // =========================================================
    // 8. SALARY ADVANCE
    // =========================================================
    @PostMapping("/salary/advance")
    public ResponseEntity<?> payAdvance(@RequestBody Map<String, Object> payload) {
        try {
            String schoolId = str(payload, "schoolId");
            String staffId = str(payload, "staffId");
            String monthKey = str(payload, "monthKey");
            Double amount = doubleOrNull(payload, "amount");

            if (isBlank(schoolId)) return badRequest("schoolId is required.");
            planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);
            if (isBlank(staffId) || isBlank(monthKey) || amount == null) {
                return badRequest("staffId, monthKey and amount are required.");
            }

            Staff staff = staffRepository.findByStaffIdAndSchoolId(staffId, schoolId).orElse(null);
            if (staff == null) return badRequest("Staff not found.");

            Finance advance = new Finance();
            advance.setSchoolId(schoolId);
            advance.setRecordType(Finance.TYPE_ADVANCE);
            advance.setStaffId(staffId);
            advance.setMonthKey(monthKey);
            advance.setAmount(amount);
            advance.setPaymentStatus("Advance");
            advance.stampApplyNow();

            return ResponseEntity.ok(financeRepository.save(advance));
        } catch (Exception e) {
            return badRequest(e.getMessage());
        }
    }

    // =========================================================
    // 9. SALARY STATUS CHECK (has this staff member been paid this month?)
    // =========================================================
    @GetMapping("/salary/status/{staffId}/{monthKey}")
    public ResponseEntity<?> getSalaryStatus(@PathVariable String staffId,
                                              @PathVariable String monthKey,
                                              @RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        Optional<Finance> record = financeRepository.findByStaffIdAndMonthKeyAndRecordTypeAndSchoolId(
                staffId, monthKey, Finance.TYPE_SALARY, schoolId);
        return ResponseEntity.ok(Map.of("paid", record.isPresent()));
    }

    /**
     * Authoritative salary history for the finance page. Staff profiles do not
     * own salaryHistory; completed payments are stored as Finance SALARY rows.
     * Returning the rows in one request lets the frontend show every current
     * staff member as Pending or Paid for the selected month.
     */
    @GetMapping("/salary/records")
    public ResponseEntity<?> getSalaryRecords(@RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        return ResponseEntity.ok(
                financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(
                        Finance.TYPE_SALARY, schoolId));
    }

    // =========================================================
    // 10. BULK LISTS — custom fees, staff bonuses, staff fines, expenses,
    //     staff advances (bulk), vouchers.
    //
    // manage-finance.js treats each of these as one flat array per school:
    //   GET  <path>?schoolId=X        -> the whole array
    //   PUT  <path> {items:[...], schoolId} -> replaces the whole array
    // See Finance.java's payloadJson docs for why these are stored as raw
    // JSON rows rather than typed columns.
    // =========================================================
    @GetMapping("/custom-fees")
    public ResponseEntity<?> getCustomFees(@RequestParam String schoolId) {
        return bulkGet(Finance.TYPE_CUSTOM_FEE, schoolId);
    }

    @PutMapping("/custom-fees")
    @Transactional
    public ResponseEntity<?> saveCustomFees(@RequestBody Map<String, Object> payload) {
        return bulkSave(Finance.TYPE_CUSTOM_FEE, payload);
    }

    @GetMapping("/staff-bonus")
    public ResponseEntity<?> getStaffBonus(@RequestParam String schoolId) {
        return bulkGet(Finance.TYPE_STAFF_BONUS, schoolId);
    }

    @PutMapping("/staff-bonus")
    @Transactional
    public ResponseEntity<?> saveStaffBonus(@RequestBody Map<String, Object> payload) {
        ResponseEntity<?> response = bulkSave(Finance.TYPE_STAFF_BONUS, payload);
        if (response.getStatusCode().is2xxSuccessful()) {
            Object items = payload.get("items");
            if (items instanceof List<?>) {
                syncSalaryRecordsWithBonuses(
                        (List<?>) items, str(payload, "schoolId"));
            }
        }
        return response;
    }

    @GetMapping("/staff-fines")
    public ResponseEntity<?> getStaffFines(@RequestParam String schoolId) {
        return bulkGet(Finance.TYPE_STAFF_FINE, schoolId);
    }

    @PutMapping("/staff-fines")
    @Transactional
    public ResponseEntity<?> saveStaffFines(@RequestBody Map<String, Object> payload) {
        return bulkSave(Finance.TYPE_STAFF_FINE, payload);
    }

    @GetMapping("/expenses")
    public ResponseEntity<?> getExpenses(@RequestParam String schoolId) {
        return bulkGet(Finance.TYPE_EXPENSE, schoolId);
    }

    @PutMapping("/expenses")
    @Transactional
    public ResponseEntity<?> saveExpenses(@RequestBody Map<String, Object> payload) {
        return bulkSave(Finance.TYPE_EXPENSE, payload);
    }

    @GetMapping("/vouchers")
    public ResponseEntity<?> getVouchers(@RequestParam String schoolId) {
        return bulkGet(Finance.TYPE_VOUCHER, schoolId);
    }

    @PutMapping("/vouchers")
    @Transactional
    public ResponseEntity<?> saveVouchers(@RequestBody Map<String, Object> payload) {
        return bulkSave(Finance.TYPE_VOUCHER, payload);
    }

    // Staff advances are a special case: real advances are already written
    // one row at a time by POST /salary/advance (TYPE_ADVANCE) and consumed
    // by paySalary()'s math above, so PUT here writes to a separate
    // TYPE_STAFF_ADVANCE_BULK bucket instead of touching those rows, and GET
    // merges both together — the frontend only reads .staffId/.amount, which
    // both shapes provide either as real columns or via payloadJson.
    @GetMapping("/staff-advances")
    public ResponseEntity<?> getStaffAdvances(@RequestParam String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        List<Object> merged = new ArrayList<>();
        for (Finance adv : financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(Finance.TYPE_ADVANCE, schoolId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("staffId", adv.getStaffId());
            item.put("amount", adv.getAmount());
            item.put("monthKey", adv.getMonthKey());
            item.put("applyDate", adv.getApplyDate());
            item.put("applyTime", adv.getApplyTime());
            item.put("paymentStatus", adv.getPaymentStatus());
            merged.add(item);
        }
        merged.addAll(bulkList(Finance.TYPE_STAFF_ADVANCE_BULK, schoolId));
        return ResponseEntity.ok(merged);
    }

    @PutMapping("/staff-advances")
    @Transactional
    public ResponseEntity<?> saveStaffAdvances(@RequestBody Map<String, Object> payload) {
        return bulkSave(Finance.TYPE_STAFF_ADVANCE_BULK, payload);
    }

    // ---- Bulk-list helpers ----
    private List<Object> bulkList(String recordType, String schoolId) {
        List<Object> result = new ArrayList<>();
        for (Finance row : financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(recordType, schoolId)) {
            try {
                result.add(objectMapper.readValue(row.getPayloadJson(), Object.class));
            } catch (Exception e) {
                // skip a row that somehow isn't valid JSON rather than failing the whole list
            }
        }
        return result;
    }

    private ResponseEntity<?> bulkGet(String recordType, String schoolId) {
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        return ResponseEntity.ok(bulkList(recordType, schoolId));
    }

    /**
     * A bonus can be entered after payroll was already paid. Keep the
     * corresponding SALARY row's bonus and netPaid columns synchronized with
     * the current STAFF_BONUS list so salary history remains accurate.
     */
    @SuppressWarnings("unchecked")
    private void syncSalaryRecordsWithBonuses(List<?> items, String schoolId) {
        if (isBlank(schoolId)) return;

        Map<String, Double> bonusTotals = new HashMap<>();
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map)) continue;

            Map<String, Object> item = (Map<String, Object>) rawItem;
            String staffId = str(item, "staffId");
            if (isBlank(staffId)) staffId = str(item, "id");
            String monthKey = str(item, "monthKey");
            Double amount;
            try {
                amount = doubleOrNull(item, "amount");
            } catch (Exception ignored) {
                amount = null;
            }

            if (isBlank(staffId) || isBlank(monthKey) || amount == null) continue;
            String key = staffId + "\u0000" + monthKey;
            bonusTotals.put(key, bonusTotals.getOrDefault(key, 0.0) + amount);
        }

        List<Finance> salaryRecords =
                financeRepository.findByRecordTypeAndSchoolIdOrderByCreatedAtAsc(
                        Finance.TYPE_SALARY, schoolId);
        for (Finance salary : salaryRecords) {
            String key = salary.getStaffId() + "\u0000" + salary.getMonthKey();
            double updatedBonus = bonusTotals.getOrDefault(key, 0.0);
            if (Math.abs(nz(salary.getBonus()) - updatedBonus) <= 0.01) continue;

            salary.setBonus(updatedBonus);
            double updatedNetPaid = nz(salary.getBaseSalary())
                    + updatedBonus
                    - nz(salary.getFines())
                    - nz(salary.getSecurityDeducted())
                    - nz(salary.getAdvanceDeducted());
            salary.setNetPaid(updatedNetPaid);
            financeRepository.save(salary);
        }
    }

    // BUGFIX — "generated voucher disappears after navigating away and
    // back" (and the equivalent risk for custom fees / staff bonus / staff
    // fines / expenses / staff advances, which all share this bulkSave
    // path): this method implements "replace the whole list" as two
    // separate statements — deleteByRecordTypeAndSchoolId(...) followed by
    // saveAll(rows) — with no transaction boundary around them. A GET
    // request from another request thread (e.g. the frontend's 10-second
    // live-sync poll) could execute in the gap between those two
    // statements and see the table with this recordType's rows already
    // deleted but not yet re-inserted, i.e. an empty list, even though the
    // save as a whole "succeeded" a moment later.
    //
    // NOTE: @Transactional is applied on each public PUT endpoint below
    // (savePlanX methods) rather than here. Spring's default @Transactional
    // support is proxy-based and only intercepts calls that come in through
    // the proxy from OUTSIDE the class — a private method called via
    // `this.bulkSave(...)` from another method in the same class bypasses
    // the proxy entirely, so annotating this private method would silently
    // do nothing.
    @SuppressWarnings("unchecked")
    private ResponseEntity<?> bulkSave(String recordType, Map<String, Object> payload) {
        String schoolId = str(payload, "schoolId");
        if (isBlank(schoolId)) return badRequest("schoolId is required.");
        // Covers custom-fees, staff-bonus, staff-fines, expenses, vouchers
        // and staff-advances PUT endpoints — they all funnel through here,
        // so gating it once gates every bulk finance write path.
        planEnforcementService.requireFeature(schoolId, PlanEnforcementService.FEATURE_FINANCE);

        Object itemsObj = payload.get("items");
        if (!(itemsObj instanceof List)) {
            return badRequest("items array is required.");
        }
        List<Object> items = (List<Object>) itemsObj;

        // Replace-the-whole-list semantics, same as the frontend's saveXData() functions.
        financeRepository.deleteByRecordTypeAndSchoolId(recordType, schoolId);

        List<Finance> rows = new ArrayList<>();
        for (Object item : items) {
            Finance row = new Finance();
            row.setSchoolId(schoolId);
            row.setRecordType(recordType);
            try {
                row.setPayloadJson(objectMapper.writeValueAsString(item));
                JsonNode node = objectMapper.valueToTree(item);
                if (node.hasNonNull("monthKey")) row.setMonthKey(node.get("monthKey").asText());
            } catch (Exception e) {
                continue; // skip an item that can't be serialized rather than failing the whole save
            }
            rows.add(row);
        }
        financeRepository.saveAll(rows);

        return ResponseEntity.ok(bulkList(recordType, schoolId));
    }

    // =========================================================
    // Helpers
    // =========================================================
    private Student findStudentInSchool(String regNoOrId, String schoolId) {
        Optional<Student> studentOpt = studentRepository.findByRegNo(regNoOrId);
        if (studentOpt.isEmpty()) {
            try {
                Long numericId = Long.parseLong(regNoOrId);
                studentOpt = studentRepository.findById(numericId);
            } catch (Exception e) {
                // not numeric — leave empty
            }
        }
        // Scope by schoolId so one school can never pull up / create finance
        // rows for a student that actually belongs to a different school.
        if (studentOpt.isPresent()
                && schoolId.equals(studentOpt.get().getSchoolId())
                && isActiveStudent(studentOpt.get())) {
            return studentOpt.get();
        }
        return null;
    }

    /**
     * Finance is an active-roster module. Student Management deliberately
     * returns archived rows as well for its Archive Center, so every finance
     * read that starts from stored ledger rows must re-check the linked
     * student's current status before returning it.
     */
    private boolean isActiveStudentInSchool(String regNo, String schoolId) {
        if (isBlank(regNo) || isBlank(schoolId)) return false;
        return studentRepository.findByRegNoAndSchoolId(regNo, schoolId)
                .map(this::isActiveStudent)
                .orElse(false);
    }

    private boolean isActiveStudent(Student student) {
        if (student == null) return false;
        String status = student.getStatus();
        return isBlank(status) || "active".equalsIgnoreCase(status.trim());
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private double nz(Double d) {
        return d != null ? d : 0.0;
    }

    private String str(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    private Double doubleOrNull(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (v == null) return null;
        return Double.parseDouble(v.toString());
    }

    private double doubleOr(Map<String, Object> payload, String key, double fallback) {
        Object v = payload.get(key);
        if (v == null) return fallback;
        return Double.parseDouble(v.toString());
    }

    private String nextMonthKey(String monthKey) {
        try {
            return YearMonth.parse(monthKey, DateTimeFormatter.ofPattern("yyyy-MM"))
                    .plusMonths(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));
        } catch (Exception e) {
            // The endpoint already requires a monthKey.  If an older client
            // sends a non-standard value, keep its original bucket rather
            // than failing an otherwise valid fine submission.
            return monthKey;
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        return Collections.singletonMap("error", message);
    }
}