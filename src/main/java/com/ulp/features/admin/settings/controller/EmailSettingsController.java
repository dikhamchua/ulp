package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.features.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.features.admin.settings.service.EmailSettingsService;
import com.ulp.security.Roles;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

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

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE      = "/admin/settings/email";
    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_SETTINGS_EMAIL = "admin/settings-email";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_DEFAULT_TEST_RECIPIENT = "defaultTestRecipient";

    // ── Flash messages ────────────────────────────────────────────
    private static final String MSG_SETTINGS_SAVED = "Email settings saved.";

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
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, service.load());
        }
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        model.addAttribute(ATTR_DEFAULT_TEST_RECIPIENT,
                principal != null ? principal.getUsername() : "");
        return VIEW_SETTINGS_EMAIL;
    }

    /**
     * Saves the SMTP configuration. Rejects OAuth2-authenticated admins because
     * {@code updated_by} requires a {@link UlpUserDetails} principal (see inline note).
     * Re-renders with inline errors on validation failure; otherwise redirects with a
     * success flash.
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
            redirectAttributes.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }
        // Validation failed — re-render with bound values + field errors.
        if (result.hasErrors()) {
            model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
            model.addAttribute(ATTR_DEFAULT_TEST_RECIPIENT, principal.getUsername());
            return VIEW_SETTINGS_EMAIL;
        }
        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SETTINGS_SAVED);
        return REDIRECT_BASE;
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
