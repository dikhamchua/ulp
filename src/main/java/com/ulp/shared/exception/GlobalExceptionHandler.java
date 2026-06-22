package com.ulp.shared.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Bat loi toan cuc cho cac controller. Sprint 0 chi xu ly o muc nen tang:
 * ghi log va tra ve trang error than thien. Cac sprint sau co the bo sung
 * handler cho cac exception nghiep vu cu the (ResourceNotFound, AccessDenied...).
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, HttpServletRequest request, Model model) {
        log.error("Loi khong xu ly tai [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        model.addAttribute("message", "Da co loi xay ra. Vui long thu lai sau.");
        return "error";
    }
}
