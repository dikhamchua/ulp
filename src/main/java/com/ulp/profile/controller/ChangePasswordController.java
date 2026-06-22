package com.ulp.profile.controller;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.profile.dto.ProfileDtos;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

/**
 * Controller cho đổi mật khẩu khi đã đăng nhập.
 * Yeu cau xac nhan mat khau hien tai truoc khi cap nhat.
 */
@Controller
public class ChangePasswordController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ChangePasswordController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/change-password")
    public String form(Model model) {
        model.addAttribute("form", new ProfileDtos.ChangePasswordRequest("", "", ""));
        return "change-password";
    }

    @PostMapping("/change-password")
    public String change(@Valid @ModelAttribute("form") ProfileDtos.ChangePasswordRequest form,
                          BindingResult result, Principal principal, Model model,
                          RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "change-password";
        }

        User user = userRepository.findByEmail(principal.getName())
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
