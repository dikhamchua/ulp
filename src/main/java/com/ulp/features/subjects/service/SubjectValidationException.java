package com.ulp.features.subjects.service;

/**
 * Business validation failure for subject catalog mutations.
 * Controllers map this to field/flash errors rather than 500.
 */
public class SubjectValidationException extends RuntimeException {

    public SubjectValidationException(String message) {
        super(message);
    }
}
