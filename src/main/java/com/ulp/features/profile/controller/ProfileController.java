package com.ulp.features.profile.controller;

import com.ulp.entities.User;
import com.ulp.features.profile.dto.ProfileDtos;
import com.ulp.features.profile.service.ProfileService;
import com.ulp.features.storage.StorageNotConfiguredException;
import com.ulp.features.upload.AvatarStorageService;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_USER;
import static com.ulp.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;
import static com.ulp.common.IConstant.MSG_STORAGE_UPLOAD_FAILED;

/**
 * Controller for viewing and updating the current user's personal profile,
 * including full name, bio, phone number, and avatar.
 */
@Controller
public class ProfileController {

    // ── View names / paths ────────────────────────────────────────
    private static final String VIEW_PROFILE     = "profile";
    private static final String REDIRECT_PROFILE = "redirect:/profile";

    // ── Multipart parameter name ──────────────────────────────────
    private static final String PARAM_AVATAR = "avatar";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_PROFILE_FORM = "profileForm";

    // ── User-facing messages ──────────────────────────────────────
    private static final String MSG_PROFILE_UPDATED = "Cập nhật hồ sơ thành công.";
    private static final String MSG_AVATAR_UPDATED  = "Cập nhật ảnh đại diện thành công.";

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
     * {@code profileForm} backed by the user's existing data.
     *
     * @param principal the authenticated principal (id sourced from Spring Security)
     * @param model     the Spring MVC model
     * @return logical view name {@code "profile"}
     */
    @GetMapping("/profile")
    public String view(@AuthenticationPrincipal UlpUserDetails principal, Model model) {
        User user = profileService.getCurrentUser(principal.getId());
        model.addAttribute(ATTR_USER, user);
        model.addAttribute(ATTR_PROFILE_FORM, new ProfileDtos.ProfileUpdateRequest(
                user.getFullName(),
                user.getBio() != null ? user.getBio() : "",
                user.getPhone() != null ? user.getPhone() : ""));
        return VIEW_PROFILE;
    }

    /**
     * Handles submission of the profile update form.
     *
     * <p>Validates the submitted {@code profileForm}. If validation fails, the
     * profile view is re-rendered with error messages. On success, the user's
     * full name, bio, and phone are persisted and the client is redirected back
     * to the profile page with a success flash message.
     */
    @PostMapping("/profile")
    public String update(@Valid @ModelAttribute("profileForm") ProfileDtos.ProfileUpdateRequest form,
                          BindingResult result,
                          @AuthenticationPrincipal UlpUserDetails principal,
                          Model model,
                          RedirectAttributes ra) {
        User user = profileService.getCurrentUser(principal.getId());
        if (result.hasErrors()) {
            model.addAttribute(ATTR_USER, user);
            return VIEW_PROFILE;
        }
        profileService.updateProfile(user, form.fullName(), form.bio(), form.phone());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PROFILE_UPDATED);
        return REDIRECT_PROFILE;
    }

    /**
     * Handles avatar upload for the currently authenticated user.
     *
     * <p>Delegates storage to {@link AvatarStorageService} and persists the
     * returned URL via {@link com.ulp.features.profile.service.ProfileService}.
     * Both the success and failure paths report through the standard flash
     * message attributes, which the page drains into a toast.
     */
    @PostMapping("/profile/avatar")
    public String uploadAvatar(@RequestParam(PARAM_AVATAR) MultipartFile file,
                                @AuthenticationPrincipal UlpUserDetails principal,
                                RedirectAttributes ra) {
        User user = profileService.getCurrentUser(principal.getId());
        try {
            String url = avatarService.store(file);
            profileService.updateAvatar(user, url);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AVATAR_UPDATED);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, e.getMessage());
        } catch (StorageNotConfiguredException e) {
            // R2 selected but incomplete — fail closed with a clear admin hint.
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_STORAGE_R2_NOT_CONFIGURED);
        } catch (IOException e) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_STORAGE_UPLOAD_FAILED);
        }
        return REDIRECT_PROFILE;
    }
}
