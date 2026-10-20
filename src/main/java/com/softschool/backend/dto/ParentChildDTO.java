package com.softschool.backend.dto;

/**
 * Light projection of Student for the parent portal: every plain column a parent screen shows, and
 * NEVER the photo / B-Form / fee LONGTEXT columns (same reason as StudentSummaryDTO). Populated by a
 * JPQL constructor expression in ParentChildRepository.
 */
public class ParentChildDTO {
    private final String schoolId;
    private final String regNo;
    private final String fullName;
    private final String rollNo;
    private final String studentClass;
    private final String section;
    private final String gender;
    private final String status;
    private final String guardianName;
    private final String guardianRole;
    private final String fatherFullName;
    private final String phone1;
    private final String phone2;
    private final String permanentAddress;
    private final Boolean hasPhoto;

    public ParentChildDTO(String schoolId, String regNo, String fullName, String rollNo,
                          String studentClass, String section, String gender, String status,
                          String guardianName, String guardianRole, String fatherFullName,
                          String phone1, String phone2, String permanentAddress, Boolean hasPhoto) {
        this.schoolId = schoolId;
        this.regNo = regNo;
        this.fullName = fullName;
        this.rollNo = rollNo;
        this.studentClass = studentClass;
        this.section = section;
        this.gender = gender;
        this.status = status;
        this.guardianName = guardianName;
        this.guardianRole = guardianRole;
        this.fatherFullName = fatherFullName;
        this.phone1 = phone1;
        this.phone2 = phone2;
        this.permanentAddress = permanentAddress;
        this.hasPhoto = hasPhoto;
    }

    public String getSchoolId() { return schoolId; }
    public String getRegNo() { return regNo; }
    public String getFullName() { return fullName; }
    public String getRollNo() { return rollNo; }
    public String getStudentClass() { return studentClass; }
    public String getSection() { return section; }
    public String getGender() { return gender; }
    public String getStatus() { return status; }
    public String getGuardianName() { return guardianName; }
    public String getGuardianRole() { return guardianRole; }
    public String getFatherFullName() { return fatherFullName; }
    public String getPhone1() { return phone1; }
    public String getPhone2() { return phone2; }
    public String getPermanentAddress() { return permanentAddress; }
    public boolean isHasPhoto() { return Boolean.TRUE.equals(hasPhoto); }
}
