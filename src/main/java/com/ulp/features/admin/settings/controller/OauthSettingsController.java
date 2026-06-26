package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.OauthSettingsDtos.OauthSettingsForm;
import com.ulp.features.admin.settings.service.OauthSettingsService;
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
 * Admin controller for the OAuth Settings screen — accessible by the
 * {@code ADMIN} role only.
 *
 * <p>Exposed URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/oauth} — render the OAuth configuration form</li>
 *   <li>{@code POST /admin/settings/oauth} — save the form (full page reload)</li>
 * </ul>
 *
 * <p>Saved values take effect on the next HTTP request because the
 * {@code DbClientRegistrationRepository} re-reads from the database on every
 * lookup — no application restart is required.
 */
@Controller
@RequestMapping("/admin/settings/oauth")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class OauthSettingsController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE      = "/admin/settings/oauth";
    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_SETTINGS_OAUTH = "admin/settings-oauth";

    // ── Flash messages ────────────────────────────────────────────
    private static final String MSG_SAVED = "Đã lưu cài đặt OAuth.";
    private static final String MSG_CLIENT_SECRET_REQUIRED =
            "Client Secret là bắt buộc khi đã nhập Client ID";

    private final OauthSettingsService service;

    public OauthSettingsController(OauthSettingsService service) {
        this.service = service;
    }

    /**
     * Renders the OAuth Settings form, pre-populated with the current
     * configuration.
     *
     * <p>If the model already contains a {@code form} attribute (e.g. after a
     * failed save redirect with flash attributes), the existing value is kept
     * so validation errors are preserved.
     *
     * @param model the Spring MVC model used to pass data to the view
     * @return the logical view name {@code admin/settings-oauth}
     */
    @GetMapping
    public String view(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, service.load());
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        return VIEW_SETTINGS_OAUTH;
    }

    /**
     * Saves the OAuth provider configuration. Rejects OAuth2-authenticated admins
     * (see {@link EmailSettingsController}). Applies a cross-field guard requiring
     * Client Secret when Client ID is set (unless a stored secret already exists).
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") OauthSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (principal == null) {
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }

        // Cross-field guard: if Client ID is set, a Client Secret must exist
        // somewhere — either submitted in this form, or already stored in DB
        // (in which case the admin intentionally leaves the field blank to
        // keep the existing value). Reject only when neither is true.
        String clientId = form.googleClientId() == null ? "" : form.googleClientId().trim();
        String submittedSecret = form.googleClientSecret() == null ? "" : form.googleClientSecret().trim();
        if (!clientId.isBlank() && submittedSecret.isBlank() && !service.hasStoredGoogleSecret()) {
            result.rejectValue("googleClientSecret",
                    "googleClientSecret.required",
                    MSG_CLIENT_SECRET_REQUIRED);
        }

        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            return VIEW_SETTINGS_OAUTH;
        }

        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SAVED);
        return REDIRECT_BASE;
    }
}
