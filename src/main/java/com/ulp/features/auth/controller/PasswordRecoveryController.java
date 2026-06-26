package com.ulp.features.auth.controller;

import com.ulp.features.auth.dto.AuthDtos;
import com.ulp.entities.User;
import com.ulp.features.auth.service.PasswordRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the forgot-password and reset-password flows.
 *
 * <p>The forgot-password endpoint is enumeration-safe: regardless of whether
 * the submitted e-mail address belongs to a registered account, the handler
 * always redirects to the same confirmation page so that an attacker cannot
 * enumerate valid addresses from the response.</p>
 */
@Controller
public class PasswordRecoveryController {

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_FORGOT_PASSWORD = "auth/forgot-password";
    private static final String VIEW_RESET_PASSWORD  = "auth/reset-password";

    // ── Paths ─────────────────────────────────────────────────────
    private static final String REDIRECT_FORGOT = "redirect:/forgot-password";
    private static final String REDIRECT_LOGIN  = "redirect:/login";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_RESET_SUCCESS = "resetSuccess";

    // ── Flash messages ────────────────────────────────────────────
    private static final String MSG_RESET_LINK_SENT =
            "Nếu email tồn tại trong hệ thống, một liên kết đặt lại mật khẩu đã được gửi. Vui lòng kiểm tra hộp thư (kể cả thư rác).";

    private final PasswordRecoveryService service;

    public PasswordRecoveryController(PasswordRecoveryService service) {
        this.service = service;
    }

    /** Renders the forgot-password form. */
    @GetMapping("/forgot-password")
    public String forgotForm(Model model) {
        model.addAttribute(ATTR_REQUEST, new AuthDtos.ForgotPasswordRequest(""));
        return VIEW_FORGOT_PASSWORD;
    }

    /**
     * Handles forgot-password submission. Always redirects to the confirmation page
     * (enumeration-safe — same response whether or not the email exists).
     */
    @PostMapping("/forgot-password")
    public String forgotSubmit(@Valid @ModelAttribute("request") AuthDtos.ForgotPasswordRequest req,
                                BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return VIEW_FORGOT_PASSWORD;
        }
        service.requestReset(req.email());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_RESET_LINK_SENT);
        return REDIRECT_FORGOT;
    }

    /**
     * Renders the reset-password form for a valid token; sets {@code invalid=true}
     * in the model when the token is missing, expired, or already used.
     */
    @GetMapping("/reset-password")
    public String resetForm(@RequestParam("token") String token, Model model) {
        User user = service.validateToken(token);
        if (user == null) {
            model.addAttribute(ATTR_INVALID, true);
            return VIEW_RESET_PASSWORD;
        }
        model.addAttribute(ATTR_TOKEN, token);
        model.addAttribute(ATTR_REQUEST, new AuthDtos.ResetPasswordRequest(token, ""));
        return VIEW_RESET_PASSWORD;
    }

    /**
     * Submits the reset-password form. Redirects to {@code /login} on success
     * with {@code resetSuccess} flash; re-renders with {@code invalid=true} on failure.
     */
    @PostMapping("/reset-password")
    public String resetSubmit(@Valid @ModelAttribute("request") AuthDtos.ResetPasswordRequest req,
                               BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute(ATTR_TOKEN, req.token());
            return VIEW_RESET_PASSWORD;
        }
        boolean ok = service.resetPassword(req.token(), req.newPassword());
        if (!ok) {
            model.addAttribute(ATTR_INVALID, true);
            return VIEW_RESET_PASSWORD;
        }
        ra.addFlashAttribute(ATTR_RESET_SUCCESS, true);
        return REDIRECT_LOGIN;
    }
}
