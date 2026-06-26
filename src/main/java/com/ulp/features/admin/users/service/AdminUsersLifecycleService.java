package com.ulp.features.admin.users.service;

import com.ulp.entities.User;
import com.ulp.entities.UserActivity;
import com.ulp.features.auth.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lifecycle state-transition operations for the {@code /admin/users}
 * screen: activate / deactivate, lock / unlock, reset-password, soft-delete
 * / restore.
 *
 * <p>Pulled out of the original {@code AdminUsersService} during the C.2
 * structural split. Last-active-admin and self-protection guards are
 * delegated to {@link AdminUsersGuard}; audit writes funnel through
 * {@link AdminUsersAuditWriter}.
 *
 * <p>Every mutation method is {@code @Transactional} and writes exactly one
 * row in {@code user_activities} per business mutation. If the audit insert
 * fails, the transaction rolls back. Mutations on {@link User} go through
 * the entity's business methods ({@code setActive}, {@code lock},
 * {@code unlock}, {@code softDelete}, {@code restore}) — direct setter
 * calls are avoided to keep the entity's surface narrow.
 */
@Service
public class AdminUsersLifecycleService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUsersGuard guard;
    private final AdminUsersAuditWriter auditWriter;

    public AdminUsersLifecycleService(UserRepository userRepository,
                                      PasswordEncoder passwordEncoder,
                                      AdminUsersGuard guard,
                                      AdminUsersAuditWriter auditWriter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public void deactivate(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "vô hiệu hoá");
        guard.requireNotLastActiveAdmin(target, "vô hiệu hoá");

        target.setActive(false);
        User saved = userRepository.save(target);
        auditWriter.write(saved.getId(), UserActivity.TYPE_DEACTIVATED,
                "Vô hiệu hoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void activate(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);

        target.setActive(true);
        User saved = userRepository.save(target);
        auditWriter.write(saved.getId(), UserActivity.TYPE_ACTIVATED,
                "Kích hoạt " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void lock(Long id, String reason, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "khoá");
        guard.requireNotLastActiveAdmin(target, "khoá");

        if (reason == null || reason.isBlank()) {
            // Defensive — the controller's @Valid LockForm already enforces this.
            throw new IllegalArgumentException("Lý do khoá tài khoản không được để trống");
        }
        // Defensive sanitisation: cap length and strip control characters that
        // could break audit-log readability if the client somehow bypasses
        // the @Size(max=255) on the form.
        String safeReason = reason.trim();
        if (safeReason.length() > 255) safeReason = safeReason.substring(0, 255);
        safeReason = safeReason.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");

        target.lock(safeReason);
        User saved = userRepository.save(target);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", safeReason);
        auditWriter.write(saved.getId(), UserActivity.TYPE_LOCKED,
                "Khoá " + saved.getEmail(),
                auditWriter.serialize(payload), actingUserId);
    }

    @Transactional
    public void unlock(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        target.unlock();
        User saved = userRepository.save(target);
        auditWriter.write(saved.getId(), UserActivity.TYPE_UNLOCKED,
                "Mở khoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "đặt lại mật khẩu");

        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Mật khẩu mới không được để trống");
        }

        target.setPasswordHash(passwordEncoder.encode(newPassword));
        User saved = userRepository.save(target);
        // Intentionally null metadata — the plaintext password must not appear
        // in the audit log.
        auditWriter.write(saved.getId(), UserActivity.TYPE_PASSWORD_RESET,
                "Đặt lại mật khẩu " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void softDelete(Long id, Long actingUserId) {
        User target = lockForLifecycle(id);
        guard.requireNotSelf(actingUserId, target.getId(), "xoá");
        guard.requireNotLastActiveAdmin(target, "xoá");

        target.softDelete();
        User saved = userRepository.save(target);
        auditWriter.write(saved.getId(), UserActivity.TYPE_DELETED,
                "Xoá " + saved.getEmail(), null, actingUserId);
    }

    @Transactional
    public void restore(Long id, Long actingUserId) {
        // Soft-deleted rows are not visible via findByIdForUpdate (the entity's
        // @SQLRestriction filters them out). Restore must therefore go through
        // the native lookup that bypasses the restriction.
        User target = userRepository.findByIdIncludingDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));

        target.restore();
        User saved = userRepository.save(target);
        auditWriter.write(saved.getId(), UserActivity.TYPE_RESTORED,
                "Khôi phục " + saved.getEmail(), null, actingUserId);
    }

    // ── Internals ─────────────────────────────────────────────────

    private User lockForLifecycle(Long id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));
    }
}
