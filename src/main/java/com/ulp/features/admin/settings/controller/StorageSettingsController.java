package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.StorageSettingsDtos;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos.StorageSettingsForm;
import com.ulp.features.admin.settings.dto.StorageSettingsDtos.TestResult;
import com.ulp.features.admin.settings.service.StorageSettingsService;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Admin controller for object storage settings (local / Cloudflare R2).
 *
 * <ul>
 *   <li>{@code GET  /admin/settings/storage} — form</li>
 *   <li>{@code POST /admin/settings/storage} — save</li>
 *   <li>{@code POST /admin/settings/storage/test} — HeadBucket JSON test</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/settings/storage")
@PreAuthorize("hasAuthority('PERM_system.storage')")
public class StorageSettingsController {

    private static final String URL_BASE = "/admin/settings/storage";
    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;
    private static final String VIEW_SETTINGS_STORAGE = "admin/settings-storage";

    private final StorageSettingsService service;

    public StorageSettingsController(StorageSettingsService service) {
        this.service = service;
    }

    /** Renders the storage settings form. */
    @GetMapping
    public String view(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, service.load());
        } else {
            // Flash/error re-render may carry a blank secret — always mask like Email.
            Object existing = model.getAttribute(ATTR_FORM);
            if (existing instanceof StorageSettingsForm form) {
                model.addAttribute(ATTR_FORM, withMaskedSecret(form));
            }
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        return VIEW_SETTINGS_STORAGE;
    }

    /** Saves storage settings; rejects OAuth-only principals (no user id). */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") StorageSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (principal == null) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }
        if (result.hasErrors()) {
            // Keep secret masked on validation re-render (blank submit must not wipe UI).
            model.addAttribute(ATTR_FORM, withMaskedSecret(form));
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            return VIEW_SETTINGS_STORAGE;
        }
        try {
            service.save(form, principal.getId());
        } catch (IllegalArgumentException ex) {
            // Business validation (R2 incomplete) — re-render with toast via flash.
            redirectAttributes.addFlashAttribute(ATTR_FORM, withMaskedSecret(form));
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return REDIRECT_BASE;
        }
        redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_STORAGE_SETTINGS_SAVED);
        return REDIRECT_BASE;
    }

    /** AJAX HeadBucket test against currently saved settings. */
    @PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TestResult testConnection() {
        return service.testConnection();
    }

    /** Same contract as Email: form secret is always the MASKED sentinel in the UI. */
    private static StorageSettingsForm withMaskedSecret(StorageSettingsForm form) {
        return new StorageSettingsForm(
                form.provider(),
                form.accountId(),
                form.accessKeyId(),
                StorageSettingsDtos.MASKED,
                form.bucket(),
                form.endpoint(),
                form.region()
        );
    }
}
