package com.softschool.backend.exception;

/**
 * Thrown when adding a new student/staff member would push a school past
 * its plan's (or its own custom override's) studentLimit/staffLimit.
 */
public class PlanLimitExceededException extends PlanEnforcementException {

    private final String schoolId;
    private final String limitType; // "students" | "staff"
    private final int limit;
    private final long current;

    public PlanLimitExceededException(String schoolId, String limitType, int limit, long current) {
        super("This school's plan allows at most " + limit + " " + limitType
                + " (currently at " + current + "). Upgrade the plan or increase the limit to add more.");
        this.schoolId = schoolId;
        this.limitType = limitType;
        this.limit = limit;
        this.current = current;
    }

    public String getSchoolId() {
        return schoolId;
    }

    public String getLimitType() {
        return limitType;
    }

    public int getLimit() {
        return limit;
    }

    public long getCurrent() {
        return current;
    }
}
