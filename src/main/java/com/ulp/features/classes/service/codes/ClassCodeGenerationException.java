package com.ulp.features.classes.service.codes;

/**
 * Thrown when {@code ClassesService} exhausts all retry attempts to generate
 * a unique class code without resolving a collision on {@code uk_classes_code}.
 *
 * <p>In practice this should almost never occur (32^5 = ~33.5 million combinations
 * plus a timestamp suffix), but if it does, {@code GlobalExceptionHandler} maps
 * this exception to HTTP 500 and returns a user-friendly error message.
 */
public class ClassCodeGenerationException extends RuntimeException {

    public ClassCodeGenerationException(String message) {
        super(message);
    }

    public ClassCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
