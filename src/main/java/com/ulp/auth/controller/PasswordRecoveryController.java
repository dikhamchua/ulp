package com.ulp.auth.controller;

import com.ulp.auth.dto.AuthDtos;
import com.ulp.auth.entity.User;
import com.ulp.auth.service.PasswordRecoveryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import jakarta.validation.Valid;

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

    private final PasswordRecoveryService service;

    /**
     * Creates a new {@code PasswordRecoveryController} with the required service dependency.
     *
     * @param service the {@link PasswordRecoveryService} used to initiate and complete password resets
     */
    public PasswordRecoveryController(PasswordRecoveryService service) {
        this.service = service;
    }

    /**
     * Displays the forgot-password form.
     *
     * <p>Binds an empty {@link com.ulp.auth.dto.AuthDtos.ForgotPasswordRequest} to the model
     * so that Thymeleaf can render the form with proper binding.</p>
     *
     * @param model the Spring MVC model used to pass data to the view
     * @return the logical view name {@code auth/forgot-password}
     */
    @GetMapping("/forgot-password")
    public String forgotForm(Model model) {
        model.addAttribute("request", new AuthDtos.ForgotPasswordRequest(""));
        return "auth/forgot-password";
    }

    /**
     * Handles submission of the forgot-password form.
     *
     * <p>If validation passes, delegates to {@link PasswordRecoveryService#requestReset(String)}
     * to send a reset link. The handler then redirects back to the same page with a
     * {@code flashSuccess} flash attribute that the view drains into a {@code UlpToast}
     * success notification (per project convention — see CLAUDE.md mục 9 &amp; 11). The
     * redirect-after-POST pattern prevents duplicate form submissions on browser refresh.</p>
     *
     * @param req    the validated forgot-password request containing the user's e-mail address
     * @param result the binding result holding any validation errors
     * @param model  the Spring MVC model (used when returning the form on validation failure)
     * @param ra     redirect attributes used to pass the {@code flashSuccess} message to the toast
     * @return the view name on validation failure, or a redirect to {@code /forgot-password} on success
     */
    @PostMapping("/forgot-password")
    public String forgotSubmit(@Valid @ModelAttribute("request") AuthDtos.ForgotPasswordRequest req,
                                BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "auth/forgot-password";
        }
        service.requestReset(req.email());
        ra.addFlashAttribute("flashSuccess",
                "Nếu email tồn tại trong hệ thống, một liên kết đặt lại mật khẩu đã được gửi. Vui lòng kiểm tra hộp thư (kể cả thư rác).");
        return "redirect:/forgot-password";
    }

    /**
     * Displays the reset-password form for a given token.
     *
     * <p>If the token is missing, expired, or has already been used,
     * {@link PasswordRecoveryService#validateToken(String)} returns {@code null} and the view
     * renders an invalid-token error message. Otherwise the token and an empty
     * {@link com.ulp.auth.dto.AuthDtos.ResetPasswordRequest} are placed in the model
     * so the form can be pre-populated and submitted correctly.</p>
     *
     * @param token the password-reset token supplied as a query parameter
     * @param model the Spring MVC model used to pass data to the view
     * @return the logical view name {@code auth/reset-password}
     */
    @GetMapping("/reset-password")
    public String resetForm(@RequestParam("token") String token, Model model) {
        User user = service.validateToken(token);
        if (user == null) {
            model.addAttribute("invalid", true);
            return "auth/reset-password";
        }
        model.addAttribute("token", token);
        model.addAttribute("request", new AuthDtos.ResetPasswordRequest(token, ""));
        return "auth/reset-password";
    }

    /**
     * Handles submission of the reset-password form.
     *
     * <p>Validates the new password and delegates to
     * {@link PasswordRecoveryService#resetPassword(String, String)} to apply the change.
     * On success the user is redirected to the login page with a {@code resetSuccess} flash
     * attribute. If the token has expired or is invalid by the time the form is submitted,
     * the view renders an error message without exposing which condition failed.</p>
     *
     * @param req    the validated reset-password request containing the token and new password
     * @param result the binding result holding any validation errors
     * @param model  the Spring MVC model (used when returning the form on validation failure)
     * @param ra     redirect attributes used to pass the {@code resetSuccess} flash flag
     * @return the view name on failure, or a redirect to {@code /login} on success
     */
    @PostMapping("/reset-password")
    public String resetSubmit(@Valid @ModelAttribute("request") AuthDtos.ResetPasswordRequest req,
                               BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            model.addAttribute("token", req.token());
            return "auth/reset-password";
        }
        boolean ok = service.resetPassword(req.token(), req.newPassword());
        if (!ok) {
            model.addAttribute("invalid", true);
            return "auth/reset-password";
        }
        ra.addFlashAttribute("resetSuccess", true);
        return "redirect:/login";
    }
}
