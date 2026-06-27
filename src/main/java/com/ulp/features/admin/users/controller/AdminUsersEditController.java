package com.ulp.features.admin.users.controller;

import com.ulp.entities.User;
import com.ulp.features.admin.users.dto.EditUserForm;
import com.ulp.features.admin.users.service.AdminUsersReadService;
import com.ulp.features.admin.users.service.AdminUsersWriteService;
import com.ulp.features.admin.users.service.EmailAlreadyUsedException;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.admin.users.controller.AdminUsersFormSupport.VIEW_FORM;
import static com.ulp.features.admin.users.controller.AdminUsersFormSupport.userUrl;

/**
 * MVC controller for the edit-user screen of {@code /admin/users}:
 * the GET {@code /{id}/edit} render (with info / activity / history tabs)
 * and the POST {@code /{id}} update.
 *
 * <p>Pulled out of {@link AdminUsersController} during the file-size refactor
 * so each controller stays focused on a single screen. Both controllers share
 * the {@code /admin/users} base mapping, the ADMIN role precondition, and
 * {@link AdminUsersFormSupport} for common form rendering.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUsersEditController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String EDIT_TAB_INFO_SUFFIX = "/edit?tab=" + TAB_INFO;

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_TARGET_USER       = "targetUser";
    private static final String ATTR_STATUS_LABEL      = "statusLabel";
    private static final String ATTR_TARGET_CREATED_AT = "targetCreatedAt";
    private static final String ATTR_ACTIVITIES_PAGE   = "activitiesPage";

    // ── Status labels (domain enum-like) ──────────────────────────
    private static final String STATUS_ACTIVE   = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String STATUS_LOCKED   = "LOCKED";
    private static final String STATUS_DELETED  = "DELETED";

    // ── Flash messages (Vietnamese UI text) ───────────────────────
    private static final String MSG_USER_UPDATED    = "Đã cập nhật tài khoản";
    private static final String MSG_EMAIL_DUPLICATE = "Email đã được sử dụng";

    /** Page size used by the "Lịch sử cập nhật" tab (fixed per Decision 4 in design.md). */
    private static final int HISTORY_PAGE_SIZE = 20;

    /** Whitelist of valid {@code tab} query-parameter values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS = Set.of(TAB_INFO, TAB_ACTIVITY, TAB_HISTORY);

    private final AdminUsersReadService readService;
    private final AdminUsersWriteService writeService;
    private final AdminUsersFormSupport formSupport;

    public AdminUsersEditController(AdminUsersReadService readService,
                                    AdminUsersWriteService writeService,
                                    AdminUsersFormSupport formSupport) {
        this.readService = readService;
        this.writeService = writeService;
        this.formSupport = formSupport;
    }

    /** Renders the edit-user form with three sub-tabs (info / activity / history). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           Model model) {
        User u = readService.getEditable(id);
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, EditUserForm.fromUser(u));
        }
        formSupport.populateFormModel(model, MODE_EDIT, id);
        model.addAttribute(ATTR_TARGET_USER, u);

        // Normalize the tab query parameter. Invalid values silently fall back
        // to "info" (per spec — no 400 / error page).
        String activeTab = VALID_TABS.contains(tab) ? tab : TAB_INFO;
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeTab);

        // Status label drives the header pill (ACTIVE | INACTIVE | LOCKED | DELETED).
        // Ordering matters: deleted > locked > inactive > active. Mirrors UserRow.statusLabel().
        model.addAttribute(ATTR_STATUS_LABEL, deriveStatusLabel(u));

        // "Tạo lúc" timestamp — the User entity does not map created_at, so
        // the service reads it via native SQL.
        model.addAttribute(ATTR_TARGET_CREATED_AT, readService.getCreatedAt(id));

        // Only query the audit history when the history tab is the one being
        // rendered. The other two tabs cost a single template render.
        if (TAB_HISTORY.equals(activeTab)) {
            int safePage = Math.max(0, page);
            model.addAttribute(ATTR_ACTIVITIES_PAGE,
                    readService.listActivities(id, PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
        }
        return VIEW_FORM;
    }

    /** Submits the edit-user form; same re-render / redirect contract as create. */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") EditUserForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            return reRenderEditForm(model, id);
        }
        try {
            List<String> warnings = writeService.update(id, form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_UPDATED);
            if (!warnings.isEmpty()) {
                ra.addFlashAttribute(ATTR_FLASH_WARNING, String.join(" ", warnings));
            }
            return "redirect:" + userUrl(id) + EDIT_TAB_INFO_SUFFIX;
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", MSG_EMAIL_DUPLICATE);
            return reRenderEditForm(model, id);
        }
    }

    /**
     * Derives the four-state status label used by the detail header pill.
     * Ordering: DELETED takes precedence over LOCKED, which takes precedence
     * over INACTIVE; otherwise ACTIVE.
     */
    private static String deriveStatusLabel(User u) {
        if (u.isDeleted()) return STATUS_DELETED;
        if (u.isLocked())  return STATUS_LOCKED;
        if (!u.isActive()) return STATUS_INACTIVE;
        return STATUS_ACTIVE;
    }

    /**
     * Common re-render path for the edit form on validation or duplicate-email
     * failure. Reloads the target user so the toolbar + header have current
     * state and pins the active detail tab to {@code info} (where the form
     * lives) so submission never bounces the user onto activity / history.
     */
    private String reRenderEditForm(Model model, Long id) {
        formSupport.populateFormModel(model, MODE_EDIT, id);
        User reloaded = readService.getEditable(id);
        model.addAttribute(ATTR_TARGET_USER, reloaded);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_STATUS_LABEL, deriveStatusLabel(reloaded));
        model.addAttribute(ATTR_TARGET_CREATED_AT, readService.getCreatedAt(id));
        return VIEW_FORM;
    }
}
