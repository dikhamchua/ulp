package com.ulp.admin.settings.controller;

import com.ulp.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.admin.settings.service.EmailSettingsService;
import com.ulp.auth.Roles;
import com.ulp.auth.service.UlpUserDetails;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin controller for the Email Settings (SMTP) screen — accessible by ADMIN role only.
 *
 * <p>Exposed URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/email}      — render the SMTP configuration form</li>
 *   <li>{@code POST /admin/settings/email}      — save the form (full page reload)</li>
 *   <li>{@code POST /admin/settings/email/test} — send a test email (AJAX, returns JSON)</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/settings/email")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class EmailSettingsController {

    private final EmailSettingsService service;

    public EmailSettingsController(EmailSettingsService service) {
        this.service = service;
    }

    /**
     * Renders the Email Settings form, pre-populated with the current SMTP configuration.
     *
     * <p>If the model already contains a {@code form} attribute (e.g. after a failed save
     * redirect with flash attributes), the existing value is kept so validation errors are
     * preserved.
     *
     * @param principal the currently authenticated admin user
     * @param model     the Spring MVC model used to pass data to the view
     * @return the logical view name {@code admin/settings-email}
     */
    @GetMapping
    public String view(@AuthenticationPrincipal UlpUserDetails principal, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", service.load());
        }
        model.addAttribute("activeTab", "settings");
        model.addAttribute("defaultTestRecipient",
                principal != null ? principal.getUsername() : "");
        return "admin/settings-email";
    }

    /**
     * Handles submission of the Email Settings form and persists the SMTP configuration.
     *
     * <p>If validation fails, the form is re-rendered with error messages.
     * On success, the settings are saved and the user is redirected back with a success flash.
     *
     * @param form               the submitted and validated SMTP settings form
     * @param result             binding result containing any validation errors
     * @param principal          the currently authenticated admin user; may be {@code null}
     *                           when the admin authenticated via Google OAuth2 (type mismatch
     *                           between {@code CustomOidcUserPrincipal} and
     *                           {@link UlpUserDetails} causes {@code @AuthenticationPrincipal}
     *                           to inject {@code null}) — see note below
     * @param model              the Spring MVC model
     * @param redirectAttributes flash attributes used to pass success/error messages across the redirect
     * @return a redirect to {@code /admin/settings/email} on success, or the view name on validation failure
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") EmailSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        // Principal can be null when the admin logs in via OAuth2 (CustomOidcUserPrincipal
        // is a different type from UlpUserDetails — @AuthenticationPrincipal injects null
        // on a type mismatch). The MVP requires user.id to stamp updated_by, so we reject
        // the request rather than throwing an NPE. When OAuth admin support is properly
        // implemented, extend the principal resolver at the Security layer.
        if (principal == null) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Your session type does not support this operation. Please log in again with email and password.");
            return "redirect:/admin/settings/email";
        }
        if (result.hasErrors()) {
            model.addAttribute("activeTab", "settings");
            model.addAttribute("defaultTestRecipient", principal.getUsername());
            return "admin/settings-email";
        }
        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute("flashSuccess", "Email settings saved.");
        return "redirect:/admin/settings/email";
    }

    /**
     * Sends a test email to the specified recipient using the currently saved SMTP settings.
     *
     * <p>This endpoint is intended for AJAX calls and returns a JSON {@link TestResult}
     * indicating whether the test delivery succeeded or failed.
     *
     * @param testRecipient the email address to send the test message to
     * @return a {@link TestResult} with the outcome of the test send
     */
    @PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TestResult sendTest(@RequestParam("testRecipient") String testRecipient) {
        return service.sendTest(testRecipient);
    }
}
