package com.softschool.backend.exception;

/**
 * Thrown when a school tries to use WhatsApp messaging after the super admin banned it - either for
 * that school directly (School.whatsappBanned) or for its whole plan (Plan.whatsappBanned).
 * Translated to HTTP 403 by PlanEnforcementExceptionHandler.
 */
public class WhatsAppBannedException extends PlanEnforcementException {

    private final String schoolId;

    public WhatsAppBannedException(String schoolId) {
        super("WhatsApp messaging is not available for your school. Please contact your administrator.");
        this.schoolId = schoolId;
    }

    public String getSchoolId() {
        return schoolId;
    }
}
