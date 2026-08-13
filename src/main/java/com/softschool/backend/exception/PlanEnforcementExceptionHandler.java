package com.softschool.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every PlanEnforcementException thrown by PlanEnforcementService into
 * a consistent JSON error response, so controllers just call
 * planEnforcementService.requireXyz(...) and don't each need their own
 * try/catch + ResponseEntity plumbing.
 *
 * Status codes:
 *   403 Forbidden — feature locked / school blocked (school exists, but is
 *                   not allowed to do this)
 *   402 Payment Required — plan limit exceeded (needs an upgrade)
 *   404 Not Found — schoolId doesn't resolve to a real school
 *   410 Gone — school's subscription has expired
 */
@RestControllerAdvice
public class PlanEnforcementExceptionHandler {

    @ExceptionHandler(FeatureLockedException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureLocked(FeatureLockedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body(
                "feature_locked", ex.getMessage(), "featureKey", ex.getFeatureKey()));
    }

    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLimitExceeded(PlanLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body(
                "plan_limit_exceeded", ex.getMessage(),
                "limitType", ex.getLimitType(),
                "limit", ex.getLimit(),
                "current", ex.getCurrent()));
    }

    @ExceptionHandler(SchoolAccessException.class)
    public ResponseEntity<Map<String, Object>> handleSchoolAccess(SchoolAccessException ex) {
        HttpStatus status;
        switch (ex.getReason()) {
            case NOT_FOUND:
                status = HttpStatus.NOT_FOUND;
                break;
            case EXPIRED:
                status = HttpStatus.GONE;
                break;
            case BLOCKED:
            default:
                status = HttpStatus.FORBIDDEN;
                break;
        }
        return ResponseEntity.status(status).body(body(
                ex.getReason().name().toLowerCase(), ex.getMessage()));
    }

    private Map<String, Object> body(String code, String message, Object... extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", code);
        map.put("message", message);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            map.put(String.valueOf(extra[i]), extra[i + 1]);
        }
        return map;
    }
}
