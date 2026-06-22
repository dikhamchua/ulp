package com.ulp.shared.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Bat loi toan cuc cho cac controller.
 *
 * <p>Sprint 2 them 2 handler chuyen biet:
 * <ul>
 *   <li>{@link EntityNotFoundException} → 404 (lop/entity khong ton tai
 *       hoac da soft-delete).</li>
 *   <li>{@link AccessDeniedException} → 403 (vi pham owner check tu
 *       service layer; Spring Security chi tu xu ly cac instance phat
 *       sinh trong filter chain hoac qua {@code @PreAuthorize}).</li>
 * </ul>
 *
 * <p>Handler chuyen biet phai dat TRUOC catch-all {@code Exception.class}
 * de Spring chon dung — Spring's exception resolver match theo thu tu
 * specificity, nhung de minh bach minh dat dung thu tu nay.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(EntityNotFoundException ex, HttpServletRequest request, Model model) {
        log.info("404 tai [{}]: {}", request.getRequestURI(), ex.getMessage());
        model.addAttribute("message",
                ex.getMessage() != null ? ex.getMessage() : "Không tìm thấy nội dung yêu cầu.");
        return "error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleForbidden(AccessDeniedException ex, HttpServletRequest request, Model model) {
        log.info("403 tai [{}]: {}", request.getRequestURI(), ex.getMessage());
        model.addAttribute("message", "Bạn không có quyền thực hiện thao tác này.");
        return "error";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        log.error("Loi khong xu ly tai [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        model.addAttribute("message", "Da co loi xay ra. Vui long thu lai sau.");
        return "error";
    }
}
