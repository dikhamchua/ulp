package com.ulp.classes.imports;

/**
 * Thrown by {@link ExcelParser} when the uploaded file fails a structural
 * validation (wrong format, too large, too many rows, missing columns, …).
 *
 * <p>The controller converts this exception into an HTTP 400 response whose
 * body carries the Vietnamese {@link #getMessage()} so the frontend can render
 * it via {@code UlpToast.error(...)}.
 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
