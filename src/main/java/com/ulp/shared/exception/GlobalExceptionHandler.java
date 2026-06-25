package com.ulp.shared.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Global exception handler for all Spring MVC controllers.
 *
 * <p>Sprint 2 introduced two specialized handlers:
 * <ul>
 *   <li>{@link EntityNotFoundException} → 404 (entity not found or
 *       already soft-deleted).</li>
 *   <li>{@link AccessDeniedException} → 403 (owner-check violation raised
 *       from the service layer; Spring Security handles instances that
 *       originate in the filter chain or via {@code @PreAuthorize}
 *       automatically).</li>
 * </ul>
 *
 * <p>Specialized handlers must be declared BEFORE the catch-all
 * {@code Exception.class} handler so that Spring picks the most specific
 * match — Spring's exception resolver does order by specificity, but the
 * explicit ordering here makes the intent clear.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles {@link EntityNotFoundException} and returns a 404 error view.
     *
     * <p>Triggered when a requested entity does not exist or has been soft-deleted.
     * The exception message is forwarded to the view when present; otherwise a
     * generic fallback message is used.
     *
     * @param ex      the exception carrying an optional detail message
     * @param request the current HTTP request (used for logging the URI)
     * @param model   the Spring MVC model to populate with the error message
     * @return the logical view name {@code "error"}
     */
    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(EntityNotFoundException ex, HttpServletRequest request, Model model) {
        log.info("404 tai [{}]: {}", request.getRequestURI(), ex.getMessage());
        model.addAttribute("message",
                ex.getMessage() != null ? ex.getMessage() : "Không tìm thấy nội dung yêu cầu.");
        return "error";
    }

    /**
     * Handles {@link AccessDeniedException} and returns a 403 error view.
     *
     * <p>Catches ownership or permission violations that are thrown explicitly
     * from the service layer. A fixed, user-friendly message is shown regardless
     * of the underlying exception detail.
     *
     * @param ex      the access-denied exception
     * @param request the current HTTP request (used for logging the URI)
     * @param model   the Spring MVC model to populate with the error message
     * @return the logical view name {@code "error"}
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleForbidden(AccessDeniedException ex, HttpServletRequest request, Model model) {
        log.info("403 tai [{}]: {}", request.getRequestURI(), ex.getMessage());
        model.addAttribute("message", "Bạn không có quyền thực hiện thao tác này.");
        return "error";
    }

    /**
     * Honors {@link ResponseStatusException} thrown from controllers
     * (e.g. {@code BAD_REQUEST}, {@code NOT_FOUND}, {@code CONFLICT}).
     * Without this handler the catch-all below converts every
     * controller-thrown status exception into a 500, masking the
     * intended HTTP code.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException ex,
                                                       HttpServletRequest request) {
        log.info("{} tai [{}]: {}", ex.getStatusCode().value(),
                request.getRequestURI(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode())
                .body(ex.getReason() != null ? ex.getReason() : "");
    }

    /**
     * Catch-all handler for any unhandled {@link Exception} and returns a 500 error view.
     *
     * <p>Logs the full stack trace at ERROR level so the incident can be investigated,
     * then shows a generic error message to the user to avoid leaking internal details.
     *
     * @param ex      the unexpected exception
     * @param request the current HTTP request (used for logging the URI)
     * @param model   the Spring MVC model to populate with the error message
     * @return the logical view name {@code "error"}
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        log.error("Loi khong xu ly tai [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        model.addAttribute("message", "Da co loi xay ra. Vui long thu lai sau.");
        return "error";
    }
}
