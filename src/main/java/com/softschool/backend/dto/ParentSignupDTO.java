package com.softschool.backend.dto;

/** The few Student columns the sign-up check needs (identity proof + sibling group). No photo / fee data. */
public class ParentSignupDTO {
    private final String schoolId;
    private final String regNo;
    private final String fatherCnic;
    private final String guardianCnic;
    private final String guardianRole;
    private final String phone1;
    private final String phone2;
    private final String siblingGroupId;

    public ParentSignupDTO(String schoolId, String regNo, String fatherCnic, String guardianCnic,
                           String guardianRole, String phone1, String phone2, String siblingGroupId) {
        this.schoolId = schoolId;
        this.regNo = regNo;
        this.fatherCnic = fatherCnic;
        this.guardianCnic = guardianCnic;
        this.guardianRole = guardianRole;
        this.phone1 = phone1;
        this.phone2 = phone2;
        this.siblingGroupId = siblingGroupId;
    }

    public String getSchoolId() { return schoolId; }
    public String getRegNo() { return regNo; }
    public String getFatherCnic() { return fatherCnic; }
    public String getGuardianCnic() { return guardianCnic; }
    public String getGuardianRole() { return guardianRole; }
    public String getPhone1() { return phone1; }
    public String getPhone2() { return phone2; }
    public String getSiblingGroupId() { return siblingGroupId; }
}
