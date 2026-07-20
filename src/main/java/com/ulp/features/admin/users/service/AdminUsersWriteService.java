package com.ulp.features.admin.users.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.entities.UserActivity;
import com.ulp.entities.UserFactory;
import com.ulp.features.admin.users.dto.CreateUserForm;
import com.ulp.features.admin.users.dto.EditUserForm;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.security.Role;
import com.ulp.utils.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Create + update operations for the {@code /admin/users} screen.
 *
 * <p>Pulled out of the original {@code AdminUsersService} during the C.2
 * structural split. Handles persistence + audit for new-user creation and
 * admin-editable field updates (including role changes and demote
 * warnings). Last-active-admin / self-role-change guards are delegated to
 * {@link AdminUsersGuard}; audit writes funnel through
 * {@link AdminUsersAuditWriter}.
 *
 * <p>Every mutation method is {@code @Transactional} and writes exactly one
 * row in {@code user_activities} per business mutation. If the audit insert
 * fails, the transaction rolls back. Mutations on {@link User} go through
 * the entity's business methods to keep the entity surface narrow.
 */
@Service
public class AdminUsersWriteService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminUsersGuard guard;
    private final AdminUsersAuditWriter auditWriter;
    private final ClassRepository classRepository;

    public AdminUsersWriteService(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  AdminUsersGuard guard,
                                  AdminUsersAuditWriter auditWriter,
                                  ClassRepository classRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.guard = guard;
        this.auditWriter = auditWriter;
        this.classRepository = classRepository;
    }

    /**
     * Persists a new user, BCrypt-encoding the chosen temporary password.
     * Email is normalised before the uniqueness check. Throws
     * {@link EmailAlreadyUsedException} if the email already exists.
     */
    @Transactional
    public User create(CreateUserForm form, Long actingUserId) {
        String email = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCase(email).ifPresent(u -> {
            throw new EmailAlreadyUsedException(email);
        });

        User u = UserFactory.newAdminCreated(
                email,
                passwordEncoder.encode(form.password()),
                form.fullName(),
                form.role(),
                form.emailVerified(),
                StringUtils.blankToNull(form.phone()),
                StringUtils.blankToNull(form.bio())
        );
        u.setDepartmentId(form.departmentId());
        User saved = userRepository.save(u);

        auditWriter.write(saved.getId(), UserActivity.TYPE_CREATED,
                "Tạo tài khoản " + saved.getEmail(), null, actingUserId);
        return saved;
    }

    /**
     * Updates the admin-editable fields of a user. Returns a list of warning
     * messages: empty when no warning applies, otherwise a single message
     * describing the demote-impact on owned classes.
     */
    @Transactional
    public List<String> update(Long id, EditUserForm form, Long actingUserId) {
        User target = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Người dùng không tồn tại"));

        // Self-role-change is forbidden.
        if (actingUserId != null && actingUserId.equals(target.getId())
                && form.role() != target.getRole()) {
            guard.requireNotSelf(actingUserId, target.getId(), "thay đổi vai trò");
        }

        // Last-admin demote guard runs only when the role is actually changing
        // away from ADMIN. It is safe to call even when role is unchanged; the
        // guard's own short-circuit handles it.
        guard.requireRoleNotDemotingLastAdmin(target, form.role());

        String newEmail = normalizeEmail(form.email());
        userRepository.findFirstByEmailIgnoreCaseAndIdNot(newEmail, target.getId())
                .ifPresent(other -> {
                    throw new EmailAlreadyUsedException(newEmail);
                });

        Map<String, Object> oldState = snapshot(target);

        Role oldRole = target.getRole();
        target.updateAdminFields(
                newEmail,
                form.fullName(),
                form.role(),
                form.emailVerified(),
                form.phone(),
                form.bio(),
                form.departmentId()
        );
        User saved = userRepository.save(target);

        Map<String, Object> newState = snapshot(saved);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("old", oldState);
        diff.put("new", newState);
        auditWriter.write(saved.getId(), UserActivity.TYPE_UPDATED,
                "Cập nhật tài khoản " + saved.getEmail(),
                auditWriter.serialize(diff), actingUserId);

        // Role-change activity is recorded as a separate row so audit consumers
        // can filter on `type = ROLE_CHANGED` without parsing UPDATED metadata.
        if (oldRole != saved.getRole()) {
            Map<String, Object> rolePayload = new LinkedHashMap<>();
            rolePayload.put("oldRole", oldRole.name());
            rolePayload.put("newRole", saved.getRole().name());
            auditWriter.write(saved.getId(), UserActivity.TYPE_ROLE_CHANGED,
                    "Đổi vai trò: " + oldRole + " → " + saved.getRole(),
                    auditWriter.serialize(rolePayload), actingUserId);
        }

        // Demote warning: if the user was LECTURER or HEAD and is now STUDENT,
        // surface affected classes so the admin can reassign them later.
        if ((oldRole == Role.LECTURER || oldRole == Role.HEAD)
                && saved.getRole() == Role.STUDENT) {
            List<ClassEntity> owned = classRepository.findAllByLecturerId(saved.getId());
            if (!owned.isEmpty()) {
                String classList = owned.stream()
                        .map(c -> c.getName() + " (" + c.getCode() + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return List.of(
                        "Người dùng đang là giảng viên của " + owned.size()
                                + " lớp: " + classList
                                + ". Vui lòng phân công lại giảng viên.");
            }
        }

        return List.of();
    }

    // ── Internals ─────────────────────────────────────────────────

    private static String normalizeEmail(String raw) {
        if (raw == null) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole() != null ? u.getRole().name() : null);
        m.put("emailVerified", u.isEmailVerified());
        m.put("phone", u.getPhone());
        m.put("bio", u.getBio());
        m.put("departmentId", u.getDepartmentId());
        return m;
    }
}
