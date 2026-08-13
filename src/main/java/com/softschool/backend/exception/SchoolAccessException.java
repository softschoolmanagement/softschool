package com.softschool.backend.exception;

/**
 * Thrown when the schoolId on a request doesn't resolve to a real school,
 * or resolves to one that is blocked / past its expiry date. Distinct from
 * FeatureLockedException / PlanLimitExceededException because it's not
 * about *which* feature — the school shouldn't be able to do anything.
 */
public class SchoolAccessException extends PlanEnforcementException {

    public enum Reason {
        NOT_FOUND,
        BLOCKED,
        EXPIRED
    }

    private final String schoolId;
    private final Reason reason;

    public SchoolAccessException(String schoolId, Reason reason) {
        super(messageFor(schoolId, reason));
        this.schoolId = schoolId;
        this.reason = reason;
    }

    private static String messageFor(String schoolId, Reason reason) {
        switch (reason) {
            case NOT_FOUND:
                return "No school found for schoolId \"" + schoolId + "\".";
            case BLOCKED:
                return "This school has been blocked by the administrator.";
            case EXPIRED:
                return "This school's subscription has expired.";
            default:
                return "This school cannot perform that action.";
        }
    }

    public String getSchoolId() {
        return schoolId;
    }

    public Reason getReason() {
        return reason;
    }
}
