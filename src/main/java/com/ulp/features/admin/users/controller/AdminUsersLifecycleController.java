package com.ulp.features.admin.users.controller;

import com.ulp.features.admin.users.dto.AdminUsersDtos.LockForm;
import com.ulp.features.admin.users.dto.AdminUsersDtos.ResetPasswordForm;
import com.ulp.features.admin.users.service.AdminUsersLifecycleService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for the lifecycle endpoints of the {@code /admin/users}
 * screen: activate, deactivate, lock, unlock, reset-password, soft-delete,
 * and restore.
 *
 * <p>CRUD endpoints (list, create, edit, update) live on
 * {@link AdminUsersController}; both controllers share the same
 * {@code /admin/users} base mapping and ADMIN role precondition.
 *
 * <p>All endpoints are restricted to the {@code ADMIN} role at the class
 * level. Lock and reset-password lifecycle errors (blank reason, blank
 * password) round-trip through flash attributes that the page-level JS
 * uses to re-open the offending modal with the previously-entered text
 * preserved.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminUsersLifecycleController {

    // ── Paths ─────────────────────────────────────────────────────
    private static final String URL_BASE      = "/admin/users";
    private static final String REDIRECT_BASE = "redirect:" + URL_BASE;

    // ── Local model attribute keys (specific to this controller) ──
    private static final String ATTR_FLASH_LOCK_FORM_VALUES  = "flashLockFormValues";
    private static final String ATTR_FLASH_RESET_FORM_VALUES = "flashResetFormValues";

    // ── Flash messages (Vietnamese UI text) ───────────────────────
    private static final String MSG_USER_ACTIVATED    = "Đã kích hoạt tài khoản";
    private static final String MSG_USER_DEACTIVATED  = "Đã vô hiệu hoá tài khoản";
    private static final String MSG_USER_LOCKED       = "Đã khoá tài khoản";
    private static final String MSG_USER_UNLOCKED     = "Đã mở khoá tài khoản";
    private static final String MSG_PASSWORD_RESET    = "Đã đặt lại mật khẩu";
    private static final String MSG_USER_DELETED      = "Đã xoá tài khoản";
    private static final String MSG_USER_RESTORED     = "Đã khôi phục tài khoản";
    private static final String MSG_LOCK_REASON_BLANK = "Lý do khoá tài khoản không được để trống";
    private static final String MSG_PASSWORD_BLANK    = "Mật khẩu mới không được để trống";

    private final AdminUsersLifecycleService lifecycleService;

    public AdminUsersLifecycleController(AdminUsersLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /** Activates a deactivated user account. */
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           RedirectAttributes ra) {
        lifecycleService.activate(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_ACTIVATED);
        return REDIRECT_BASE;
    }

    /** Deactivates an active user account (login blocked). */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        lifecycleService.deactivate(id, user.getId());
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
        lifecycleService.lock(id, lockForm.lockedReason(), user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_LOCKED);
        return REDIRECT_BASE;
    }

    /** Unlocks a locked user account. */
    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        lifecycleService.unlock(id, user.getId());
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
        lifecycleService.resetPassword(id, resetForm.newPassword(), user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PASSWORD_RESET);
        return REDIRECT_BASE;
    }

    /** Soft-deletes a user (sets is_deleted; account becomes recoverable via restore). */
    @PostMapping("/{id}/delete")
    public String softDelete(@PathVariable Long id,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        lifecycleService.softDelete(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_DELETED);
        return REDIRECT_BASE;
    }

    /** Restores a soft-deleted user. */
    @PostMapping("/{id}/restore")
    public String restore(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        lifecycleService.restore(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_USER_RESTORED);
        return REDIRECT_BASE;
    }

    /** Flash payload used by the page-level JS to re-open the Lock modal. */
    public record ModalReopenLockValues(Long userId, String reason) {}

    /** Flash payload used by the page-level JS to re-open the Reset Password modal. */
    public record ModalReopenResetValues(Long userId) {}
}
