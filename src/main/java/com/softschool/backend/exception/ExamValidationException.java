package com.softschool.backend.exception;

/** A client-correctable problem with an Examination request (maps to HTTP 400). */
public class ExamValidationException extends RuntimeException {
    public ExamValidationException(String message) {
        super(message);
    }
}
