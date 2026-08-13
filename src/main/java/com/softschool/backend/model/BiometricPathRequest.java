package com.softschool.backend.model;

public class BiometricPathRequest {
    private String path;

    // NEW: which school this biometric device/.mdb file belongs to.
    // Needed because Staff is now looked up by (schoolId, staffId),
    // not by a single global ID — see Staff.java.
    private String schoolId;

    // Standard getter and setter
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getSchoolId() { return schoolId; }
    public void setSchoolId(String schoolId) { this.schoolId = schoolId; }
}