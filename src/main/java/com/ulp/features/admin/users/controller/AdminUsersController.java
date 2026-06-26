package com.ulp.features.admin.users.controller;

import com.ulp.features.admin.users.dto.AdminUsersDtos.CreateUserForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.EditUserForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.LockForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.ResetPasswordForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.StatusFilter;
import com.ulp.features.admin.users.dto.AdminUsersDtos.UserFilter;
import com.ulp.features.admin.users.dto.AdminUsersDtos.UserRow;
import com.ulp.features.admin.users.dto.DepartmentReference;
import com.ulp.features.admin.users.service.AdminUsersService;
import com.ulp.features.admin.users.service.EmailAlreadyUsedException;
import com.ulp.security.Role;
import com.ulp.security.Roles;
import com.ulp.entities.User;
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
 * MVC controller for the {@code /admin/users} screen.
 *
 * <p>All endpoints are restricted to the {@code ADMIN} role at the class
 * level. CSRF protection is provided by Spring Security for every POST.
 * Validation errors render inline on the form templates; lifecycle action
 * errors (blank lock reason, blank password) round-trip through flash
 * attributes that the page-level JS uses to re-open the offending modal
 * with the previously-entered text preserved.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUsersController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE          = "/admin/users";
    private static final String REDIRECT_BASE     = "redirect:" + URL_BASE;
    private static final String EDIT_TAB_INFO_SUFFIX = "/edit?tab=" + TAB_INFO;

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_LIST = "admin/users";
    private static final String VIEW_FORM = "admin/users-form";

    // ── Local model attribute keys (specific to this controller) ──
    private static final String ATTR_PAGE                    = "page";
    private static final String ATTR_FILTER                  = "filter";
    private static final String ATTR_ROLES                   = "roles";
    private static final String ATTR_STATUSES                = "statuses";
    private static final String ATTR_CURRENT_USER_ID         = "currentUserId";
    private static final String ATTR_DEPARTMENTS             = "departments";
    private static final String ATTR_TARGET_USER             = "targetUser";
    private static final String ATTR_STATUS_LABEL            = "statusLabel";
    private static final String ATTR_TARGET_CREATED_AT       = "targetCreatedAt";
    private static final String ATTR_ACTIVITIES_PAGE         = "activitiesPage";
    private static final String ATTR_LOCK_FORM               = "lockForm";
    private static final String ATTR_RESET_FORM              = "resetForm";
    private static final String ATTR_FLASH_LOCK_FORM_VALUES  = "flashLockFormValues";
    private static final String ATTR_FLASH_RESET_FORM_VALUES = "flashResetFormValues";

    // ── Status labels (domain enum-like) ──────────────────────────
    private static final String STATUS_ACTIVE   = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String STATUS_LOCKED   = "LOCKED";
    private static final String STATUS_DELETED  = "DELETED";

    // ── Flash messages (Vietnamese UI text) ───────────────────────
    private static final String MSG_USER_CREATED      = "Đã tạo tài khoản ";
    private static final String MSG_USER_UPDATED      = "Đã cập nhật tài khoản";
    private static final String MSG_USER_ACTIVATED    = "Đã kích hoạt tài khoản";
    private static final String MSG_USER_DEACTIVATED  = "Đã vô hiệu hoá tài khoản";
    private static final String MSG_USER_LOCKED       = "Đã khoá tài khoản";
    private static final String MSG_USER_UNLOCKED     = "Đã mở khoá tài khoản";
    private static final String MSG_PASSWORD_RESET    = "Đã đặt lại mật khẩu";
    private static final String MSG_USER_DELETED      = "Đã xoá tài khoản";
    private static final String MSG_USER_RESTORED     = "Đã khôi phục tài khoản";
    private static final String MSG_EMAIL_DUPLICATE   = "Email đã được sử dụng";
    private static final String MSG_LOCK_REASON_BLANK = "Lý do khoá tài khoản không được để trống";
    private static final String MSG_PASSWORD_BLANK    = "Mật khẩu mới không được để trống";

    private final AdminUsersService usersService;

    public AdminUsersController(AdminUsersService usersService) {
        this.usersService = usersService;
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
        Page<UserRow> page = usersService.list(filter, pageable);

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
            User saved = usersService.create(form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_CREATED + saved.getEmail());
            return REDIRECT_BASE;
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", MSG_EMAIL_DUPLICATE);
            populateFormModel(model, MODE_CREATE, null);
            return VIEW_FORM;
        }
    }

    // ── Edit form ─────────────────────────────────────────────────

    /** Page size used by the "Lịch sử cập nhật" tab (fixed per Decision 4 in design.md). */
    private static final int HISTORY_PAGE_SIZE = 20;

    /** Whitelist of valid {@code tab} query-parameter values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS = Set.of(TAB_INFO, TAB_ACTIVITY, TAB_HISTORY);

    /** Renders the edit-user form with three sub-tabs (info / activity / history). */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           Model model) {
        User u = usersService.getEditable(id);
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
        model.addAttribute(ATTR_TARGET_CREATED_AT, usersService.getCreatedAt(id));

        // Only query the audit history when the history tab is the one being
        // rendered. The other two tabs cost a single template render.
        if (TAB_HISTORY.equals(activeTab)) {
            int safePage = Math.max(0, page);
            model.addAttribute(ATTR_ACTIVITIES_PAGE,
                    usersService.listActivities(id, PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
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
            populateFormModel(model, MODE_EDIT, id);
            User reloaded = usersService.getEditable(id);
            model.addAttribute(ATTR_TARGET_USER, reloaded);
            // Validation errors always re-render the info tab — that's where
            // the form lives. Keep the detail model attributes in sync so the
            // template can render the toolbar + header without NPE.
            model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
            model.addAttribute(ATTR_STATUS_LABEL, deriveStatusLabel(reloaded));
            model.addAttribute(ATTR_TARGET_CREATED_AT, usersService.getCreatedAt(id));
            return VIEW_FORM;
        }
        try {
            List<String> warnings = usersService.update(id, form, user.getId());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_UPDATED);
            if (!warnings.isEmpty()) {
                ra.addFlashAttribute(ATTR_FLASH_WARNING, String.join(" ", warnings));
            }
            return "redirect:" + userUrl(id) + EDIT_TAB_INFO_SUFFIX;
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", MSG_EMAIL_DUPLICATE);
            populateFormModel(model, MODE_EDIT, id);
            User reloaded = usersService.getEditable(id);
            model.addAttribute(ATTR_TARGET_USER, reloaded);
            model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
            model.addAttribute(ATTR_STATUS_LABEL, deriveStatusLabel(reloaded));
            model.addAttribute(ATTR_TARGET_CREATED_AT, usersService.getCreatedAt(id));
            return VIEW_FORM;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /** Activates a deactivated user account. */
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           RedirectAttributes ra) {
        usersService.activate(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_ACTIVATED);
        return REDIRECT_BASE;
    }

    /** Deactivates an active user account (login blocked). */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        usersService.deactivate(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_DEACTIVATED);
        return REDIRECT_BASE;
    }

    /** Locks a user account with a reason; re-opens the modal on validation failure. */
    @PostMapping("/{id}/lock")
    public String lock(@PathVariable Long id,
                       @Valid @ModelAttribute("lockForm") LockForm lockForm,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails user,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_LOCK_REASON_BLANK);
            ra.addFlashAttribute(ATTR_FLASH_LOCK_FORM_VALUES,
                    new ModalReopenLockValues(id, lockForm.lockedReason()));
            return REDIRECT_BASE;
        }
        usersService.lock(id, lockForm.lockedReason(), user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_LOCKED);
        return REDIRECT_BASE;
    }

    /** Unlocks a locked user account. */
    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        usersService.unlock(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_UNLOCKED);
        return REDIRECT_BASE;
    }

    /** Resets a user's password; re-opens the modal on validation failure. */
    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm resetForm,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_PASSWORD_BLANK);
            ra.addFlashAttribute(ATTR_FLASH_RESET_FORM_VALUES, new ModalReopenResetValues(id));
            return REDIRECT_BASE;
        }
        usersService.resetPassword(id, resetForm.newPassword(), user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PASSWORD_RESET);
        return REDIRECT_BASE;
    }

    /** Soft-deletes a user (sets is_deleted; account becomes recoverable via restore). */
    @PostMapping("/{id}/delete")
    public String softDelete(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        usersService.softDelete(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_DELETED);
        return REDIRECT_BASE;
    }

    /** Restores a soft-deleted user. */
    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        usersService.restore(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_RESTORED);
        return REDIRECT_BASE;
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

    /** Builds the canonical URL for a single admin user. */
    private static String userUrl(Long id) {
        return URL_BASE + "/" + id;
    }

    /** Flash payload used by the page-level JS to re-open the Lock modal. */
    public record ModalReopenLockValues(Long userId, String reason) {}

    /** Flash payload used by the page-level JS to re-open the Reset Password modal. */
    public record ModalReopenResetValues(Long userId) {}
}
