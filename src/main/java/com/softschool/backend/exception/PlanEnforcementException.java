package com.softschool.backend.exception;

/**
 * Base type for every "this school isn't allowed to do that" failure —
 * locked features, exceeded student/staff limits, blocked/expired schools,
 * or an unknown schoolId. Thrown from PlanEnforcementService and translated
 * into an HTTP response by PlanEnforcementExceptionHandler, so individual
 * controllers never need their own try/catch or response-formatting logic.
 */
public abstract class PlanEnforcementException extends RuntimeException {

    public PlanEnforcementException(String message) {
        super(message);
    }
}
