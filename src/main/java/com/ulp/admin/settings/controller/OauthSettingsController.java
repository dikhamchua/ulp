package com.ulp.admin.settings.controller;

import com.ulp.admin.settings.dto.OauthSettingsDtos.OauthSettingsForm;
import com.ulp.admin.settings.service.OauthSettingsService;
import com.ulp.auth.Roles;
import com.ulp.auth.service.UlpUserDetails;
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
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", service.load());
        }
        model.addAttribute("activeTab", "settings");
        return "admin/settings-oauth";
    }

    /**
     * Handles submission of the OAuth Settings form and persists the
     * provider configuration.
     *
     * <p>If validation fails, the form is re-rendered with error messages.
     * On success, the settings are saved and the user is redirected back
     * with a success flash.
     *
     * @param form               the submitted and validated settings form
     * @param result             binding result containing any validation errors
     * @param principal          the currently authenticated admin user; may be
     *                           {@code null} when the admin signed in via Google
     *                           OAuth2 (the principal type is then
     *                           {@code CustomOidcUserPrincipal}, not
     *                           {@link UlpUserDetails}), in which case the save
     *                           is rejected because {@code updated_by} cannot
     *                           be stamped
     * @param model              the Spring MVC model
     * @param redirectAttributes flash attributes used to pass success/error messages across the redirect
     * @return a redirect to {@code /admin/settings/oauth} on success, or the view name on validation failure
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") OauthSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        if (principal == null) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Phiên đăng nhập của bạn không hỗ trợ thao tác này. Vui lòng đăng nhập lại bằng email và mật khẩu.");
            return "redirect:/admin/settings/oauth";
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
                    "Client Secret là bắt buộc khi đã nhập Client ID");
        }

        if (result.hasErrors()) {
            model.addAttribute("activeTab", "settings");
            return "admin/settings-oauth";
        }

        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute("flashSuccess", "Đã lưu cài đặt OAuth.");
        return "redirect:/admin/settings/oauth";
    }
}
