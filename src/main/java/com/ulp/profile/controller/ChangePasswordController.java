package com.ulp.profile.controller;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.auth.service.UlpUserDetails;
import com.ulp.profile.dto.ProfileDtos;
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

/**
 * Controller for the change-password feature available to authenticated users.
 *
 * <p>Requires the user to confirm their current password before a new password
 * is accepted, preventing unauthorised password changes on unattended sessions.</p>
 */
@Controller
public class ChangePasswordController {

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
        model.addAttribute("form", new ProfileDtos.ChangePasswordRequest("", "", ""));
        return "change-password";
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
            return "change-password";
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));

        // Verify current password
        if (!passwordEncoder.matches(form.currentPassword(), user.getPasswordHash())) {
            model.addAttribute("wrongCurrent", true);
            return "change-password";
        }

        // Confirm match
        if (!form.newPassword().equals(form.confirmPassword())) {
            model.addAttribute("mismatch", true);
            return "change-password";
        }

        user.setPasswordHash(passwordEncoder.encode(form.newPassword()));
        userRepository.save(user);

        ra.addFlashAttribute("passwordChanged", true);
        return "redirect:/change-password";
    }
}
