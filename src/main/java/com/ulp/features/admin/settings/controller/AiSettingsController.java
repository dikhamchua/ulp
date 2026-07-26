package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.AiSettingsDtos.AiProviderForm;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.RevealKeyResult;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.TestResult;
import com.ulp.features.admin.settings.service.AiProviderService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

import static com.ulp.common.IConstant.*;

/**
 * Admin controller for the AI Provider Settings screen.
 *
 * <p>Exposed URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/ai}             — list providers + add/edit panel</li>
 *   <li>{@code GET  /admin/settings/ai/{id}/edit}   — load one provider into the panel</li>
 *   <li>{@code POST /admin/settings/ai}             — create or update (full page reload)</li>
 *   <li>{@code POST /admin/settings/ai/{id}/toggle} — enable / disable</li>
 *   <li>{@code POST /admin/settings/ai/{id}/delete} — hard delete</li>
 *   <li>{@code POST /admin/settings/ai/{id}/test}   — ping the provider (AJAX, JSON)</li>
 *   <li>{@code GET  /admin/settings/ai/{id}/key}    — reveal the API key (AJAX, JSON)</li>
 * </ul>
 *
 * <p>Authorization is layered: the {@code /admin/**} matcher in {@code SecurityConfig}
 * restricts the whole area to ADMIN, and the {@code system.ai} permission below adds a
 * finer gate so this screen can be withheld from an individual admin through a REVOKE
 * override without touching their role.
 *
 * <p>The stored API keys never reach the list HTML — the table renders a fixed dot run
 * and the real value is fetched only through the dedicated reveal endpoint, which is
 * behind the same permission gate.
 */
@Controller
@RequestMapping("/admin/settings/ai")
@PreAuthorize("hasAuthority('PERM_system.ai')")
public class AiSettingsController {

    private static final String REDIRECT_BASE = "redirect:" + URL_SETTINGS_AI;

    private final AiProviderService service;

    public AiSettingsController(AiProviderService service) {
        this.service = service;
    }

    /**
     * Renders the provider table plus the add/edit panel.
     *
     * <p>A {@code form} already in the model (flashed back from a rejected POST) is kept
     * so inline field errors and the user's input survive the redirect.
     *
     * @param model the Spring MVC model
     * @return the logical view name {@code admin/settings-ai}
     */
    @GetMapping
    public String list(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, AiProviderForm.empty());
        }
        populate(model);
        return VIEW_SETTINGS_AI;
    }

    /**
     * Loads one provider into the edit panel. Falls back to the empty add panel with an
     * error flash when the id no longer exists.
     *
     * @param id provider identifier
     * @return the settings view, or a redirect when the provider is gone
     */
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<AiProviderForm> form = service.loadForm(id);
        if (form.isEmpty()) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROVIDER_NOT_FOUND);
            return REDIRECT_BASE;
        }
        model.addAttribute(ATTR_FORM, form.get());
        populate(model);
        return VIEW_SETTINGS_AI;
    }

    /**
     * Creates a new provider or updates an existing one, depending on whether the form
     * carries an id.
     *
     * <p>Two checks run beyond bean validation, both reported as inline field errors
     * rather than toasts because they belong to a specific input:
     * a duplicate name, and a missing API key on create.
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") AiProviderForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes ra) {
        // Principal is null for an OAuth2-authenticated admin (different principal type),
        // and updated_by needs a user id — reject rather than throwing an NPE.
        if (principal == null) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }
        // Name uniqueness surfaces next to the field; the DB unique key is the last guard.
        if (!result.hasFieldErrors("name") && service.isNameTaken(form.name(), form.id())) {
            result.rejectValue("name", "duplicate", MSG_AI_NAME_DUPLICATE);
        }
        // On create the key is mandatory; on edit a blank value means "keep the stored key".
        if (form.id() == null && (form.apiKey() == null || form.apiKey().isBlank())) {
            result.rejectValue("apiKey", "required", MSG_AI_KEY_REQUIRED);
        }
        if (result.hasErrors()) {
            populate(model);
            return VIEW_SETTINGS_AI;
        }

        if (form.id() == null) {
            service.create(form, principal.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_CREATED);
        } else if (service.update(form, principal.getId())) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_UPDATED);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROVIDER_NOT_FOUND);
        }
        return REDIRECT_BASE;
    }

    /** Enables or disables one provider; disabled providers are skipped by {@code AiClient}. */
    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails principal,
                         RedirectAttributes ra) {
        if (principal == null) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_OAUTH_SESSION_UNSUPPORTED);
            return REDIRECT_BASE;
        }
        Optional<Boolean> nowEnabled = service.toggleEnabled(id, principal.getId());
        if (nowEnabled.isEmpty()) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROVIDER_NOT_FOUND);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    nowEnabled.get() ? MSG_AI_PROVIDER_ENABLED : MSG_AI_PROVIDER_DISABLED);
        }
        return REDIRECT_BASE;
    }

    /** Hard-deletes one provider. Surviving rows keep their display order. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        if (service.delete(id)) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_DELETED);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROVIDER_NOT_FOUND);
        }
        return REDIRECT_BASE;
    }

    /**
     * Sends a one-message ping to the given provider and reports the outcome as JSON.
     *
     * <p>Returns 200 with {@code {"ok":false,"error":...}} on a logical failure so the
     * client can toast the reason; a non-2xx here means a transport or auth problem.
     *
     * @param id provider identifier
     * @return the test outcome
     */
    @PostMapping(value = "/{id}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TestResult test(@PathVariable Long id) {
        return service.test(id);
    }

    /**
     * Returns the plain API key of one provider, for the eye and copy buttons.
     *
     * <p>This is deliberately a separate request rather than a hidden field: the key must
     * not exist anywhere in the initial page source. The endpoint inherits the class-level
     * {@code system.ai} permission gate.
     *
     * @param id provider identifier
     * @return the key, or an error payload when the provider is gone
     */
    @GetMapping(value = "/{id}/key", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RevealKeyResult revealKey(@PathVariable Long id) {
        return service.revealKey(id)
                .map(RevealKeyResult::ok)
                .orElseGet(() -> RevealKeyResult.fail(MSG_AI_PROVIDER_NOT_FOUND));
    }

    // ─────────────────────────────────────────────────────────────────

    /** Adds the table rows and sidebar state shared by every render of this screen. */
    private void populate(Model model) {
        model.addAttribute(ATTR_AI_PROVIDERS, service.listRows());
        model.addAttribute(ATTR_AI_ALL_DISABLED, service.hasProvidersButAllDisabled());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
    }
}
