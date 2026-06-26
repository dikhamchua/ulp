package com.ulp.features.admin.users.controller;

import com.ulp.entities.User;
import com.ulp.features.admin.users.dto.AdminUsersDtos.CreateUserForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.EditUserForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.StatusFilter;
import com.ulp.features.admin.users.dto.AdminUsersDtos.UserFilter;
import com.ulp.features.admin.users.dto.AdminUsersDtos.UserRow;
import com.ulp.features.admin.users.dto.DepartmentReference;
import com.ulp.features.admin.users.service.AdminUsersReadService;
import com.ulp.features.admin.users.service.AdminUsersWriteService;
import com.ulp.features.admin.users.service.EmailAlreadyUsedException;
import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import com.ulp.utils.StringUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

/**
 * MVC controller for the CRUD endpoints of the {@code /admin/users} screen
 * (list, create, edit, update).
 *
 * <p>Lifecycle endpoints (activate / deactivate / lock / unlock /
 * reset-password / delete / restore) live on
 * {@link AdminUsersLifecycleController}; both controllers share the same
 * {@code /admin/users} base mapping and ADMIN role precondition.
 *
 * <p>All endpoints are restricted to the {@code ADMIN} role at the class
 * level. CSRF protection is provided by Spring Security for every POST.
 * Validation errors render inline on the form templates.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUsersController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE             = "/admin/users";
    private static final String REDIRECT_BASE        = "redirect:" + URL_BASE;
    private static final String EDIT_TAB_INFO_SUFFIX = "/edit?tab=" + TAB_INFO;

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_LIST = "admin/users";
    private static final String VIEW_FORM = "admin/users-form";

    // ── Local model attribute keys (specific to this controller) ──
    private static final String ATTR_PAGE              = "page";
    private static final String ATTR_FILTER            = "filter";
    private static final String ATTR_ROLES             = "roles";
    private static final String ATTR_STATUSES          = "statuses";
    private static final String ATTR_CURRENT_USER_ID   = "currentUserId";
    private static final String ATTR_DEPARTMENTS       = "departments";
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
    private static final String MSG_USER_CREATED    = "Đã tạo tài khoản ";
    private static final String MSG_USER_UPDATED    = "Đã cập nhật tài khoản";
    private static final String MSG_EMAIL_DUPLICATE = "Email đã được sử dụng";

    /** Page size used by the "Lịch sử cập nhật" tab (fixed per Decision 4 in design.md). */
    private static final int HISTORY_PAGE_SIZE = 20;

    /** Whitelist of valid {@code tab} query-parameter values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS = Set.of(TAB_INFO, TAB_ACTIVITY, TAB_HISTORY);

    private final AdminUsersReadService readService;
    private final AdminUsersWriteService writeService;

    public AdminUsersController(AdminUsersReadService readService,
                                AdminUsersWriteService writeService) {
        this.readService = readService;
        this.writeService = writeService;
    }

    // ── List ──────────────────────────────────────────────────────

    /** Lists users with optional filters (search, role, status, sort). */
    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String sort,
                       Pageable pageable,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        UserFilter filter = new UserFilter(
                q,
                StringUtils.blankToNull(role),
                StatusFilter.normalize(status),
                StringUtils.blankToNull(sort)
        );
        Page<UserRow> page = readService.list(filter, pageable);

        model.addAttribute(ATTR_PAGE, page);
        model.addAttribute(ATTR_FILTER, filter);
        model.addAttribute(ATTR_ROLES, Role.values());
        model.addAttribute(ATTR_STATUSES, StatusFilter.values());
        model.addAttribute(ATTR_CURRENT_USER_ID, user.getId());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
        return VIEW_LIST;
    }

    // ── Create form ───────────────────────────────────────────────

    /** Renders the create-user form, preserving flashed values from a failed POST. */
    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, CreateUserForm.empty());
        }
        populateFormModel(model, MODE_CREATE, null);
        return VIEW_FORM;
    }

    /** Submits the create-user form; re-renders inline on error, redirects to list on success. */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") CreateUserForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, MODE_CREATE, null);
            return VIEW_FORM;
        }
        try {
            User saved = writeService.create(form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_CREATED + saved.getEmail());
            return REDIRECT_BASE;
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", MSG_EMAIL_DUPLICATE);
            populateFormModel(model, MODE_CREATE, null);
            return VIEW_FORM;
        }
    }

    // ── Edit form ─────────────────────────────────────────────────

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
        populateFormModel(model, MODE_EDIT, id);
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

    // ── Helpers ───────────────────────────────────────────────────

    private void populateFormModel(Model model, String mode, Long userId) {
        model.addAttribute(ATTR_MODE, mode);
        model.addAttribute(ATTR_FORM_ACTION,
                MODE_CREATE.equals(mode) ? URL_BASE : userUrl(userId));
        model.addAttribute(ATTR_ROLES, Role.values());
        // TODO Sprint 6: replace with DepartmentRepository.findAll() once Departments capability ships.
        model.addAttribute(ATTR_DEPARTMENTS, DepartmentReference.DEFAULT_LIST);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_USERS);
    }

    /**
     * Common re-render path for the edit form on validation or duplicate-email
     * failure. Reloads the target user so the toolbar + header have current
     * state and pins the active detail tab to {@code info} (where the form
     * lives) so submission never bounces the user onto activity / history.
     */
    private String reRenderEditForm(Model model, Long id) {
        populateFormModel(model, MODE_EDIT, id);
        User reloaded = readService.getEditable(id);
        model.addAttribute(ATTR_TARGET_USER, reloaded);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_STATUS_LABEL, deriveStatusLabel(reloaded));
        model.addAttribute(ATTR_TARGET_CREATED_AT, readService.getCreatedAt(id));
        return VIEW_FORM;
    }

    /** Builds the canonical URL for a single admin user. */
    private static String userUrl(Long id) {
        return URL_BASE + "/" + id;
    }
}
