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
 * Controller for viewing and updating the current user's personal profile,
 * including full name, bio, phone number, and avatar.
 */
@Controller
public class ProfileController {

    private final ProfileService profileService;
    private final AvatarStorageService avatarService;

    public ProfileController(ProfileService profileService, AvatarStorageService avatarService) {
        this.profileService = profileService;
        this.avatarService = avatarService;
    }

    /**
     * Displays the profile page for the currently authenticated user.
     *
     * <p>Populates the model with the {@link User} entity and a pre-filled
     * {@code profileForm} backed by the user's existing data.</p>
     *
     * @param principal the currently authenticated principal
     * @param model     the Spring MVC model
     * @return logical view name {@code "profile"}
     */
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

    /**
     * Handles submission of the profile update form.
     *
     * <p>Validates the submitted {@code profileForm}. If validation fails, the
     * profile view is re-rendered with error messages. On success, the user's
     * full name, bio, and phone are persisted and the client is redirected back
     * to the profile page with a {@code profileUpdated} flash attribute.</p>
     *
     * @param form      the validated profile update request
     * @param result    the binding/validation result
     * @param principal the currently authenticated principal
     * @param model     the Spring MVC model (used on validation failure)
     * @param ra        redirect attributes for flash messages
     * @return {@code "profile"} view on error, or a redirect to {@code /profile} on success
     */
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

    /**
     * Handles avatar upload for the currently authenticated user.
     *
     * <p>Delegates storage to {@link AvatarStorageService} and persists the
     * returned URL via {@link com.ulp.profile.service.ProfileService}. On
     * success, an {@code avatarUpdated} flash attribute is set. On failure, an
     * {@code avatarError} flash attribute is set with a human-readable message.</p>
     *
     * @param file      the uploaded avatar image (multipart)
     * @param principal the currently authenticated principal
     * @param ra        redirect attributes for flash messages
     * @return a redirect to {@code /profile}
     */
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
            ra.addFlashAttribute("avatarError", "Could not save file, please try again");
        }
        return "redirect:/profile";
    }
}
