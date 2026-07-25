package com.ulp.features.admin.permissions.controller;

import com.ulp.features.admin.permissions.dto.PermissionDtos.OverrideForm;
import com.ulp.features.admin.permissions.service.PermissionOverrideService;
import com.ulp.security.UlpUserDetails;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the per-user permission override screen at
 * {@code /admin/permissions/overrides}.
 *
 * <p>Authorization is layered exactly as on {@link AdminPermissionsController}: the
 * {@code /admin/**} matcher pins the controller to ADMIN, and
 * {@code PERM_system.permissions} adds the finer gate.
 *
 * <p>A blank reason is reported back as an inline field error (via a dedicated flash
 * attribute the template renders next to the field), not as a toast — per the project's
 * rule that field-level validation stays inline.
 */
@Controller
@RequestMapping(URL_ADMIN_PERMISSIONS_OVERRIDES)
@PreAuthorize("hasAuthority('PERM_system.permissions')")
public class AdminPermissionOverridesController {

    private static final String REDIRECT_BASE = "redirect:" + URL_ADMIN_PERMISSIONS_OVERRIDES;

    /** Blank form backing a first render, so the template never dereferences null. */
    private static final OverrideForm EMPTY_OVERRIDE_FORM =
            new OverrideForm(null, null, null, null, null);

    private final PermissionOverrideService overrideService;

    public AdminPermissionOverridesController(PermissionOverrideService overrideService) {
        this.overrideService = overrideService;
    }

    /** Renders the override list plus the create form. */
    @GetMapping
    public String list(Model model) {
        // Absent unless a prior POST failed validation and flashed its values back.
        if (!model.containsAttribute(ATTR_OVERRIDE_FORM)) {
            model.addAttribute(ATTR_OVERRIDE_FORM, EMPTY_OVERRIDE_FORM);
        }
        model.addAttribute(ATTR_PERMISSION_OVERRIDES, overrideService.listOverrides());
        model.addAttribute(ATTR_PERMISSION_CATALOG, overrideService.listCatalog());
        model.addAttribute(ATTR_OVERRIDE_USERS, overrideService.listCandidates());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_PERMISSIONS);
        return VIEW_ADMIN_PERMISSIONS_OVERRIDES;
    }

    /**
     * Creates an override, or replaces the existing one for the same user + permission.
     *
     * <p>{@code expiresAt} is optional — an absent value means the override never expires.
     */
    @PostMapping
    public String save(@RequestParam Long userId,
                       @RequestParam String featureKey,
                       @RequestParam String overrideType,
                       @RequestParam(required = false) String reason,
                       @RequestParam(required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime expiresAt,
                       @AuthenticationPrincipal UlpUserDetails user,
                       RedirectAttributes ra) {
        OverrideForm form = new OverrideForm(userId, featureKey, overrideType, reason, expiresAt);
        try {
            overrideService.createOrReplace(form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_SAVED);
        } catch (IllegalArgumentException ex) {
            // Field-level validation stays inline; the template renders it by the field.
            ra.addFlashAttribute(ATTR_FLASH_REASON_ERROR, ex.getMessage());
            // Flash the submitted values back so the redirect does not blank the form.
            ra.addFlashAttribute(ATTR_OVERRIDE_FORM, form);
        }
        return REDIRECT_BASE;
    }

    /** Switches an override off while keeping its row as history. */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        overrideService.deactivate(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_DEACTIVATED);
        return REDIRECT_BASE;
    }
}
