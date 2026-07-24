package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.GeneralSettingsDtos.GeneralSettingsForm;
import com.ulp.features.admin.settings.service.GeneralSettingsService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Admin controller for the General Settings screen — accessible by the
 * {@code ADMIN} role only.
 *
 * <p>Exposed URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/general} — render the platform configuration form</li>
 *   <li>{@code POST /admin/settings/general} — save the form (full page reload)</li>
 * </ul>
 *
 * <p>General settings (site name, description, logo URL, contact email) are
 * plain, non-secret values persisted to the {@code system_settings} GENERAL
 * group. They are stored for administration; consuming them at render time
 * (header logo, page title) is out of scope for this MVP.
 */
@Controller
@RequestMapping("/admin/settings/general")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class GeneralSettingsController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE      = "/admin/settings/general";
    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_SETTINGS_GENERAL = "admin/settings-general";

    // ── Flash messages ────────────────────────────────────────────
    private static final String MSG_SAVED = "Đã lưu cài đặt chung.";

    private final GeneralSettingsService service;

    public GeneralSettingsController(GeneralSettingsService service) {
        this.service = service;
    }

    /**
     * Renders the General Settings form, pre-populated with the current values.
     *
     * <p>If the model already contains a {@code form} attribute (e.g. after a
     * failed save that re-renders), the existing value is kept so validation
     * errors are preserved.
     *
     * @param model the Spring MVC model used to pass data to the view
     * @return the logical view name {@code admin/settings-general}
     */
    @GetMapping
    public String view(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, service.load());
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        return VIEW_SETTINGS_GENERAL;
    }

    /**
     * Saves the General settings. On validation failure re-renders the form
     * with bound values and field errors; on success redirects back with a
     * success flash message.
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") GeneralSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (principal == null) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }

        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            return VIEW_SETTINGS_GENERAL;
        }

        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SAVED);
        return REDIRECT_BASE;
    }
}
