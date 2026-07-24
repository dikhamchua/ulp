package com.ulp.features.questionbank.service;

/** Validation error surfaced to SSR controllers as a toast or inline message. */
public class QuestionBankValidationException extends RuntimeException {

    public QuestionBankValidationException(String message) {
        super(message);
    }
}
