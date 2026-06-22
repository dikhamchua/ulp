package com.ulp.profile.controller;

import com.ulp.auth.entity.User;
import com.ulp.profile.dto.ProfileDtos;
import com.ulp.profile.service.ProfileService;
import com.ulp.shared.upload.AvatarStorageService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.security.Principal;

/**
 * Controller cho xem va cap nhat hồ sơ cá nhân (full name, bio, phone, avatar).
 */
@Controller
public class ProfileController {

    private final ProfileService profileService;
    private final AvatarStorageService avatarService;

    public ProfileController(ProfileService profileService, AvatarStorageService avatarService) {
        this.profileService = profileService;
        this.avatarService = avatarService;
    }

    @GetMapping("/profile")
    public String view(Principal principal, Model model) {
        User user = profileService.getCurrentUser(principal);
        model.addAttribute("user", user);
        model.addAttribute("profileForm", new ProfileDtos.ProfileUpdateRequest(
                user.getFullName(),
                user.getBio() != null ? user.getBio() : "",
                user.getPhone() != null ? user.getPhone() : ""));
        return "profile";
    }

    @PostMapping("/profile")
    public String update(@Valid @ModelAttribute("profileForm") ProfileDtos.ProfileUpdateRequest form,
                          BindingResult result, Principal principal, Model model,
                          RedirectAttributes ra) {
        User user = profileService.getCurrentUser(principal);
        if (result.hasErrors()) {
            model.addAttribute("user", user);
            return "profile";
        }
        profileService.updateProfile(user, form.fullName(), form.bio(), form.phone());
        ra.addFlashAttribute("profileUpdated", true);
        return "redirect:/profile";
    }

    @PostMapping("/profile/avatar")
    public String uploadAvatar(@RequestParam("avatar") MultipartFile file,
                                Principal principal, RedirectAttributes ra) {
        User user = profileService.getCurrentUser(principal);
        try {
            String url = avatarService.store(file);
            profileService.updateAvatar(user, url);
            ra.addFlashAttribute("avatarUpdated", true);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("avatarError", e.getMessage());
        } catch (IOException e) {
            ra.addFlashAttribute("avatarError", "Không thể lưu file, vui lòng thử lại");
        }
        return "redirect:/profile";
    }
}
