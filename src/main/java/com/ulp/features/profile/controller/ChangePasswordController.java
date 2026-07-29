package com.ulp.features.profile.controller;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.profile.service.SessionRevocationService;
import com.ulp.security.UlpUserDetails;
import com.ulp.features.profile.dto.ProfileDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Controller for the change-password feature available to authenticated users.
 *
 * <p>Requires the user to confirm their current password before a new password
 * is accepted, preventing unauthorised password changes on unattended sessions.</p>
 *
 * <p>A successful change also revokes the user's other sessions. The old
 * password authorised those sessions, so leaving them alive would mean a
 * stolen session survives the very action taken to shut it out.</p>
 */
@Controller
public class ChangePasswordController {

    // ── View names / paths ────────────────────────────────────────
    private static final String VIEW_CHANGE_PASSWORD     = "change-password";
    private static final String REDIRECT_CHANGE_PASSWORD = "redirect:/change-password";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_WRONG_CURRENT    = "wrongCurrent";
    private static final String ATTR_MISMATCH        = "mismatch";

    // ── User-facing messages ──────────────────────────────────────
    private static final String MSG_PASSWORD_CHANGED =
            "Đổi mật khẩu thành công.";
    private static final String MSG_PASSWORD_CHANGED_SESSIONS_REVOKED =
            "Đổi mật khẩu thành công. Các phiên đăng nhập khác đã bị đăng xuất.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationService sessionRevocationService;

    public ChangePasswordController(UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     SessionRevocationService sessionRevocationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevocationService = sessionRevocationService;
    }

    /**
     * Displays the change-password form.
     *
     * @param model the Spring MVC model used to bind an empty {@link ProfileDtos.ChangePasswordRequest}
     * @return the logical view name {@code "change-password"}
     */
    @GetMapping("/change-password")
    public String form(Model model) {
        model.addAttribute(ATTR_FORM, new ProfileDtos.ChangePasswordRequest("", "", ""));
        return VIEW_CHANGE_PASSWORD;
    }

    /**
     * Processes the change-password form submission.
     *
     * <p>Validation steps performed in order:</p>
     * <ol>
     *   <li>Bean-validation errors on the form — redisplay the form if any.</li>
     *   <li>Current password verification against the stored BCrypt hash.</li>
     *   <li>New password / confirm-password match check.</li>
     * </ol>
     * On success the new password is BCrypt-encoded and persisted, the user's
     * other sessions are revoked, and the browser is redirected back to the
     * form with a success flash message.
     *
     * @param form      the submitted {@link ProfileDtos.ChangePasswordRequest} (validated)
     * @param result    binding result carrying any constraint violations
     * @param principal the authenticated principal — id sourced from Spring Security
     * @param request   the current request, used to identify the session to keep
     * @param model     the Spring MVC model for error flags
     * @param ra        redirect attributes used to pass the success flash message
     * @return a redirect to {@code /change-password} on success, or the view name
     *         {@code "change-password"} when validation fails
     * @throws IllegalStateException if the authenticated principal cannot be found in the database
     */
    @PostMapping("/change-password")
    public String change(@Valid @ModelAttribute("form") ProfileDtos.ChangePasswordRequest form,
                          BindingResult result,
                          @AuthenticationPrincipal UlpUserDetails principal,
                          HttpServletRequest request,
                          Model model,
                          RedirectAttributes ra) {
        if (result.hasErrors()) {
            return VIEW_CHANGE_PASSWORD;
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        // Reject if current password doesn't match — blocks unattended-session abuse.
        if (!passwordEncoder.matches(form.currentPassword(), user.getPasswordHash())) {
            model.addAttribute(ATTR_WRONG_CURRENT, true);
            return VIEW_CHANGE_PASSWORD;
        }

        // Reject when confirm field doesn't match new password — typo safety net.
        if (!form.newPassword().equals(form.confirmPassword())) {
            model.addAttribute(ATTR_MISMATCH, true);
            return VIEW_CHANGE_PASSWORD;
        }

        user.setPasswordHash(passwordEncoder.encode(form.newPassword()));
        userRepository.save(user);

        // The old password authorised every live session, so revoke them all —
        // except this one, which the user is actively working in.
        String currentSessionId = request.getSession(false) == null
                ? null
                : request.getSession(false).getId();
        int revoked = sessionRevocationService.revokeOtherSessions(
                user.getEmail(), currentSessionId);

        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, revoked > 0
                ? MSG_PASSWORD_CHANGED_SESSIONS_REVOKED
                : MSG_PASSWORD_CHANGED);
        return REDIRECT_CHANGE_PASSWORD;
    }
}
