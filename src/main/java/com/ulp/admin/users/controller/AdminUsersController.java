package com.ulp.admin.users.controller;

import com.ulp.admin.users.dto.AdminUsersDtos.CreateUserForm;
import com.ulp.admin.users.dto.AdminUsersDtos.EditUserForm;
import com.ulp.admin.users.dto.AdminUsersDtos.LockForm;
import com.ulp.admin.users.dto.AdminUsersDtos.ResetPasswordForm;
import com.ulp.admin.users.dto.AdminUsersDtos.StatusFilter;
import com.ulp.admin.users.dto.AdminUsersDtos.UserFilter;
import com.ulp.admin.users.dto.AdminUsersDtos.UserRow;
import com.ulp.admin.users.dto.DepartmentReference;
import com.ulp.admin.users.service.AdminUsersService;
import com.ulp.admin.users.service.EmailAlreadyUsedException;
import com.ulp.auth.Role;
import com.ulp.auth.Roles;
import com.ulp.auth.entity.User;
import com.ulp.auth.service.UlpUserDetails;
import com.ulp.shared.util.StringUtils;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        User u = usersService.getEditable(id);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", EditUserForm.fromUser(u));
        }
        populateFormModel(model, "edit", id);
        model.addAttribute("targetUser", u);
        return "admin/users-form";
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
            model.addAttribute("targetUser", usersService.getEditable(id));
            return "admin/users-form";
        }
        try {
            List<String> warnings = usersService.update(id, form, user.getId());
            ra.addFlashAttribute("flashSuccess", "Đã cập nhật tài khoản");
            if (!warnings.isEmpty()) {
                ra.addFlashAttribute("flashWarning", String.join(" ", warnings));
            }
            return "redirect:/admin/users";
        } catch (EmailAlreadyUsedException ex) {
            result.rejectValue("email", "email.duplicate", "Email đã được sử dụng");
            populateFormModel(model, "edit", id);
            model.addAttribute("targetUser", usersService.getEditable(id));
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
