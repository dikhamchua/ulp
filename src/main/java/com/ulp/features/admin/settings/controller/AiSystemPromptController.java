package com.ulp.features.admin.settings.controller;

import com.ulp.features.admin.settings.dto.AiSystemPromptDtos.AiSystemPromptForm;
import com.ulp.features.admin.settings.service.AiSystemPromptService;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

import static com.ulp.common.IConstant.*;

/**
 * Admin controller for the AI System Prompt catalog.
 *
 * <p>Exposed URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/ai/prompts}             — list prompts + add/edit panel</li>
 *   <li>{@code GET  /admin/settings/ai/prompts/{id}/edit}   — load one prompt into the panel</li>
 *   <li>{@code POST /admin/settings/ai/prompts}             — create or update (full page reload)</li>
 *   <li>{@code POST /admin/settings/ai/prompts/{id}/toggle} — enable / disable</li>
 *   <li>{@code POST /admin/settings/ai/prompts/{id}/delete} — hard delete</li>
 * </ul>
 *
 * <p>The literal {@code /prompts} segment makes every mapping here strictly more specific
 * than {@code AiSettingsController}'s {@code /admin/settings/ai/{id}/...} patterns, so
 * Spring routes to this controller without ambiguity.
 *
 * <p>Authorization is layered: the {@code /admin/**} matcher in {@code SecurityConfig}
 * restricts the whole area to ADMIN, and the {@code system.ai} permission below adds a
 * finer gate — the same gate the provider screen uses, so an admin who may configure AI
 * providers may also curate the prompts those providers are called with.
 */
@Controller
@RequestMapping(URL_SETTINGS_AI_PROMPTS)
@PreAuthorize("hasAuthority('PERM_system.ai')")
public class AiSystemPromptController {

    private static final String REDIRECT_BASE = "redirect:" + URL_SETTINGS_AI_PROMPTS;

    private final AiSystemPromptService service;

    public AiSystemPromptController(AiSystemPromptService service) {
        this.service = service;
    }

    /**
     * Renders the prompt catalog plus the add/edit panel.
     *
     * <p>A {@code form} already in the model (flashed back from a rejected POST) is kept
     * so inline field errors and the user's input survive the redirect.
     *
     * @param model the Spring MVC model
     * @return the logical view name {@code admin/settings-ai-prompts}
     */
    @GetMapping
    public String list(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, AiSystemPromptForm.empty());
        }
        populate(model);
        return VIEW_SETTINGS_AI_PROMPTS;
    }

    /**
     * Loads one prompt into the edit panel. Falls back to the empty add panel with an
     * error flash when the id no longer exists.
     *
     * @param id prompt identifier
     * @return the catalog view, or a redirect when the prompt is gone
     */
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<AiSystemPromptForm> form = service.loadForm(id);
        if (form.isEmpty()) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND);
            return REDIRECT_BASE;
        }
        model.addAttribute(ATTR_FORM, form.get());
        populate(model);
        return VIEW_SETTINGS_AI_PROMPTS;
    }

    /**
     * Creates a new prompt or updates an existing one, depending on whether the form
     * carries an id.
     *
     * <p>Duplicate names are reported as an inline field error rather than a toast,
     * because the problem belongs to a specific input.
     */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") AiSystemPromptForm form,
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
            result.rejectValue("name", "duplicate", MSG_AI_PROMPT_NAME_DUPLICATE);
        }
        if (result.hasErrors()) {
            populate(model);
            return VIEW_SETTINGS_AI_PROMPTS;
        }

        if (form.id() == null) {
            service.create(form, principal.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_CREATED);
        } else if (service.update(form, principal.getId())) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_UPDATED);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND);
        }
        return REDIRECT_BASE;
    }

    /** Enables or disables one prompt; disabled prompts are withheld from the pickers. */
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
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                    nowEnabled.get() ? MSG_AI_PROMPT_ENABLED : MSG_AI_PROMPT_DISABLED);
        }
        return REDIRECT_BASE;
    }

    /** Hard-deletes one prompt. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        if (service.delete(id)) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_DELETED);
        } else {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND);
        }
        return REDIRECT_BASE;
    }

    // ─────────────────────────────────────────────────────────────────

    /** Adds the table rows and sidebar state shared by every render of this screen. */
    private void populate(Model model) {
        model.addAttribute(ATTR_AI_PROMPTS, service.listRows());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
    }
}
