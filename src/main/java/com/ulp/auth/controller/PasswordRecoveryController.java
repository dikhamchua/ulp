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
 * Controller cho quen mat khau / dat lai mat khau.
 * Enumeration-safe: forgot-password luon tra ve cung 1 trang xac nhan.
 */
@Controller
public class PasswordRecoveryController {

    private final PasswordRecoveryService service;

    public PasswordRecoveryController(PasswordRecoveryService service) {
        this.service = service;
    }

    @GetMapping("/forgot-password")
    public String forgotForm(Model model) {
        model.addAttribute("request", new AuthDtos.ForgotPasswordRequest(""));
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotSubmit(@Valid @ModelAttribute("request") AuthDtos.ForgotPasswordRequest req,
                                BindingResult result, Model model, RedirectAttributes ra) {
        if (result.hasErrors()) {
            return "auth/forgot-password";
        }
        service.requestReset(req.email());
        ra.addFlashAttribute("sent", true);
        return "redirect:/forgot-password";
    }

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
