package com.ulp.features.admin.categories.service;

/**
 * Domain exception raised when a category operation violates a business rule
 * that is not a plain field-validation error — e.g. a blocked delete (has
 * children / still linked to courses) or a two-level hierarchy breach.
 *
 * <p>The controller catches this and surfaces {@link #getMessage()} as an
 * error toast via the flash → toast pattern; it carries a ready-to-display
 * Vietnamese message rather than a code.
 */
public class CategoryValidationException extends RuntimeException {

    public CategoryValidationException(String message) {
        super(message);
    }
}
