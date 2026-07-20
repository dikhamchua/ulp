package com.ulp.features.admin.departments.service;

/**
 * Domain exception for department business-rule breaches (duplicate code,
 * ineligible head, missing row). Message is Vietnamese UI text for toasts.
 */
public class DepartmentValidationException extends RuntimeException {

    public DepartmentValidationException(String message) {
        super(message);
    }
}
