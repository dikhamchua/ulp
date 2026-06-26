package com.ulp.features.profile.controller;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.security.UlpUserDetails;
import com.ulp.features.profile.dto.ProfileDtos;
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
 */
@Controller
public class ChangePasswordController {

    // ── View names / paths ────────────────────────────────────────
    private static final String VIEW_CHANGE_PASSWORD     = "change-password";
    private static final String REDIRECT_CHANGE_PASSWORD = "redirect:/change-password";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_WRONG_CURRENT    = "wrongCurrent";
    private static final String ATTR_MISMATCH        = "mismatch";
    private static final String ATTR_PASSWORD_CHANGED = "passwordChanged";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ChangePasswordController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
     * On success the new password is BCrypt-encoded, persisted, and the user is
     * redirected back to the form with a {@code passwordChanged} flash attribute.
     *
     * @param form      the submitted {@link ProfileDtos.ChangePasswordRequest} (validated)
     * @param result    binding result carrying any constraint violations
     * @param principal the authenticated principal — id sourced from Spring Security
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

        ra.addFlashAttribute(ATTR_PASSWORD_CHANGED, true);
        return REDIRECT_CHANGE_PASSWORD;
    }
}
