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

    private final AdminUsersService usersService;

    public AdminUsersController(AdminUsersService usersService) {
        this.usersService = usersService;
    }

    // ── List ──────────────────────────────────────────────────────

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

        model.addAttribute("page", page);
        model.addAttribute("filter", filter);
        model.addAttribute("roles", Role.values());
        model.addAttribute("statuses", StatusFilter.values());
        model.addAttribute("currentUserId", user.getId());
        model.addAttribute("activeTab", "users");
        return "admin/users";
    }

    // ── Create form ───────────────────────────────────────────────

    @GetMapping("/new")
    public String createForm(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", CreateUserForm.empty());
        }
        populateFormModel(model, "create", null);
        return "admin/users-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CreateUserForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, "create", null);
            return "admin/users-form";
        }
        try {
            User saved = usersService.create(form, user.getId());
            ra.addFlashAttribute("flashSuccess", "Đã tạo tài khoản " + saved.getEmail());
            return "redirect:/admin/users";
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", "Email đã được sử dụng");
            populateFormModel(model, "create", null);
            return "admin/users-form";
        }
    }

    // ── Edit form ─────────────────────────────────────────────────

    /** Page size used by the "Lịch sử cập nhật" tab (fixed per Decision 4 in design.md). */
    private static final int HISTORY_PAGE_SIZE = 20;

    /** Whitelist of valid {@code tab} query-parameter values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS = Set.of("info", "activity", "history");

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = "info") String tab,
                           @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                           Model model) {
        User u = usersService.getEditable(id);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", EditUserForm.fromUser(u));
        }
        populateFormModel(model, "edit", id);
        model.addAttribute("targetUser", u);

        // Normalize the tab query parameter. Invalid values silently fall back
        // to "info" (per spec — no 400 / error page).
        String activeTab = VALID_TABS.contains(tab) ? tab : "info";
        model.addAttribute("activeDetailTab", activeTab);

        // Status label drives the header pill (ACTIVE | INACTIVE | LOCKED | DELETED).
        // Ordering matters: deleted > locked > inactive > active. Mirrors UserRow.statusLabel().
        model.addAttribute("statusLabel", deriveStatusLabel(u));

        // "Tạo lúc" timestamp — the User entity does not map created_at, so
        // the service reads it via native SQL.
        model.addAttribute("targetCreatedAt", usersService.getCreatedAt(id));

        // Only query the audit history when the history tab is the one being
        // rendered. The other two tabs cost a single template render.
        if ("history".equals(activeTab)) {
            int safePage = Math.max(0, page);
            model.addAttribute("activitiesPage",
                    usersService.listActivities(id, PageRequest.of(safePage, HISTORY_PAGE_SIZE)));
        }
        return "admin/users-form";
    }

    /**
     * Derives the four-state status label used by the detail header pill.
     * Ordering: DELETED takes precedence over LOCKED, which takes precedence
     * over INACTIVE; otherwise ACTIVE.
     */
    private static String deriveStatusLabel(User u) {
        if (u.isDeleted()) return "DELETED";
        if (u.isLocked())  return "LOCKED";
        if (!u.isActive()) return "INACTIVE";
        return "ACTIVE";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") EditUserForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            populateFormModel(model, "edit", id);
            User reloaded = usersService.getEditable(id);
            model.addAttribute("targetUser", reloaded);
            // Validation errors always re-render the info tab — that's where
            // the form lives. Keep the detail model attributes in sync so the
            // template can render the toolbar + header without NPE.
            model.addAttribute("activeDetailTab", "info");
            model.addAttribute("statusLabel", deriveStatusLabel(reloaded));
            model.addAttribute("targetCreatedAt", usersService.getCreatedAt(id));
            return "admin/users-form";
        }
        try {
            List<String> warnings = usersService.update(id, form, user.getId());
            ra.addFlashAttribute("flashSuccess", "Đã cập nhật tài khoản");
            if (!warnings.isEmpty()) {
                ra.addFlashAttribute("flashWarning", String.join(" ", warnings));
            }
            return "redirect:/admin/users/" + id + "/edit?tab=info";
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", "Email đã được sử dụng");
            populateFormModel(model, "edit", id);
            User reloaded = usersService.getEditable(id);
            model.addAttribute("targetUser", reloaded);
            model.addAttribute("activeDetailTab", "info");
            model.addAttribute("statusLabel", deriveStatusLabel(reloaded));
            model.addAttribute("targetCreatedAt", usersService.getCreatedAt(id));
            return "admin/users-form";
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           RedirectAttributes ra) {
        usersService.activate(id, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã kích hoạt tài khoản");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        usersService.deactivate(id, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã vô hiệu hoá tài khoản");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/lock")
    public String lock(@PathVariable Long id,
                       @Valid @ModelAttribute("lockForm") LockForm lockForm,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails user,
                       RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute("flashError", "Lý do khoá tài khoản không được để trống");
            ra.addFlashAttribute("flashLockFormValues",
                    new ModalReopenLockValues(id, lockForm.lockedReason()));
            return "redirect:/admin/users";
        }
        usersService.lock(id, lockForm.lockedReason(), user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã khoá tài khoản");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        usersService.unlock(id, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã mở khoá tài khoản");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @Valid @ModelAttribute("resetForm") ResetPasswordForm resetForm,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            ra.addFlashAttribute("flashError", "Mật khẩu mới không được để trống");
            ra.addFlashAttribute("flashResetFormValues", new ModalReopenResetValues(id));
            return "redirect:/admin/users";
        }
        usersService.resetPassword(id, resetForm.newPassword(), user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã đặt lại mật khẩu");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String softDelete(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        usersService.softDelete(id, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã xoá tài khoản");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        usersService.restore(id, user.getId());
        ra.addFlashAttribute("flashSuccess", "Đã khôi phục tài khoản");
        return "redirect:/admin/users";
    }

    // ── Helpers ───────────────────────────────────────────────────

    private void populateFormModel(Model model, String mode, Long classId) {
        model.addAttribute("mode", mode);
        model.addAttribute("formAction",
                "create".equals(mode) ? "/admin/users" : "/admin/users/" + classId);
        model.addAttribute("roles", Role.values());
        // TODO Sprint 6: replace with DepartmentRepository.findAll() once Departments capability ships.
        model.addAttribute("departments", DepartmentReference.DEFAULT_LIST);
        model.addAttribute("activeTab", "users");
    }

    /** Flash payload used by the page-level JS to re-open the Lock modal. */
    public record ModalReopenLockValues(Long userId, String reason) {}

    /** Flash payload used by the page-level JS to re-open the Reset Password modal. */
    public record ModalReopenResetValues(Long userId) {}
}
