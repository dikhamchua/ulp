package com.ulp.features.admin.users.service;

import com.ulp.entities.Permission;
import com.ulp.entities.User;
import com.ulp.entities.UserActivity;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.EffectivePermissionRepository;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRow;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.admin.permissions.service.AdminPermissionsGuard;
import com.ulp.features.admin.permissions.service.PermissionResolver;
import com.ulp.features.auth.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Toggles one permission for one user straight from the user edit screen.
 *
 * <p>The screen has no reason field: an admin ticks or unticks a row and the change
 * applies immediately. The justification is therefore system-generated, and the
 * change is recorded in {@code user_activities} so it appears in the account's
 * "Lịch sử cập nhật" tab alongside every other administrative mutation.
 *
 * <p>What the toggle writes depends on what the user's role already gives them:
 * ticking a permission the role lacks creates a {@code GRANT}; unticking one the
 * role supplies creates a {@code REVOKE}; returning a row to its role-derived state
 * deactivates the override rather than deleting it, keeping the row as history.
 */
@Service
public class UserPermissionToggleService {

    private static final String MSG_UNKNOWN_USER = "Không tìm thấy người dùng";
    private static final String MSG_UNKNOWN_PERMISSION = "Không tìm thấy quyền: ";
    private static final String MSG_CORE_LOCKED =
            "Không thể thay đổi quyền cốt lõi của quản trị viên.";

    /** Reason stored on overrides created here; this screen never asks for one. */
    private static final String SYSTEM_REASON = "Điều chỉnh trực tiếp từ trang chi tiết tài khoản";

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final EffectivePermissionRepository effectivePermissionRepository;
    private final AdminPermissionsGuard guard;
    private final AdminUsersAuditWriter auditWriter;
    private final PermissionResolver permissionResolver;

    public UserPermissionToggleService(UserRepository userRepository,
                                       PermissionRepository permissionRepository,
                                       UserPermissionOverrideRepository overrideRepository,
                                       EffectivePermissionRepository effectivePermissionRepository,
                                       AdminPermissionsGuard guard,
                                       AdminUsersAuditWriter auditWriter,
                                       PermissionResolver permissionResolver) {
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.overrideRepository = overrideRepository;
        this.effectivePermissionRepository = effectivePermissionRepository;
        this.guard = guard;
        this.auditWriter = auditWriter;
        this.permissionResolver = permissionResolver;
    }

    /**
     * Applies a tick or untick on one permission row.
     *
     * @param userId     the user whose permission is changing
     * @param featureKey the permission being toggled
     * @param granted    {@code true} when the admin ticked the row
     * @param actorId    the acting admin's user id
     * @return the permission's human-readable name, for the confirmation toast
     * @throws AccessDeniedException  when the row is a frozen core ADMIN permission
     * @throws NoSuchElementException when the user or feature key is unknown
     */
    @Transactional
    public String toggle(Long userId, String featureKey, boolean granted, Long actorId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException(MSG_UNKNOWN_USER));
        Permission permission = permissionRepository.findByFeatureKey(featureKey)
                .orElseThrow(() -> new NoSuchElementException(MSG_UNKNOWN_PERMISSION + featureKey));

        String roleCode = user.getRole() == null ? null : user.getRole().name();
        if (guard.isCoreAdminPermission(roleCode, permission.getPermissionGroup(), featureKey)) {
            throw new AccessDeniedException(MSG_CORE_LOCKED);
        }

        boolean fromRole = rolePermissionIds(userId).contains(permission.getId());
        Optional<UserPermissionOverride> existing =
                overrideRepository.findByUserIdAndPermissionId(userId, permission.getId());

        String before = describe(fromRole, activeTypeOf(existing));
        String after = describe(fromRole, newOverrideType(granted, fromRole));

        applyChange(userId, permission.getId(), granted, fromRole, existing, actorId);
        writeAudit(userId, permission, before, after, actorId);
        permissionResolver.evictUser(userId);
        return permission.getName();
    }

    /**
     * Writes the override row implied by the new checkbox state.
     *
     * <p>When the requested state already matches what the role gives, any override
     * is switched off instead of being replaced — the row falls back to inheritance.
     */
    private void applyChange(Long userId, Long permissionId, boolean granted, boolean fromRole,
                             Optional<UserPermissionOverride> existing, Long actorId) {
        String type = newOverrideType(granted, fromRole);
        if (type == null) {
            existing.filter(o -> o.isInEffect(LocalDateTime.now()))
                    .ifPresent(UserPermissionOverride::deactivate);
            return;
        }
        if (existing.isPresent()) {
            // Update in place — a delete-then-insert would violate idx_upo_user_perm.
            existing.get().replaceWith(type, SYSTEM_REASON, actorId, null);
        } else {
            overrideRepository.save(new UserPermissionOverride(
                    userId, permissionId, type, SYSTEM_REASON, actorId, null));
        }
    }

    /** Records the change on the account's audit timeline as "from → to". */
    private void writeAudit(Long userId, Permission permission,
                            String before, String after, Long actorId) {
        String message = "Đã cập nhật quyền " + permission.getName()
                + " (" + permission.getFeatureKey() + "): " + before + " → " + after;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("featureKey", permission.getFeatureKey());
        payload.put("permissionGroup", permission.getPermissionGroup());
        payload.put("before", before);
        payload.put("after", after);
        auditWriter.write(userId, UserActivity.TYPE_PERMISSION_CHANGED, message,
                auditWriter.serialize(payload), actorId);
    }

    /** Reads the permission ids the user's role chain grants, inheritance expanded. */
    private Set<Long> rolePermissionIds(Long userId) {
        Set<Long> ids = new HashSet<>();
        for (RolePermissionRow row : effectivePermissionRepository.findRoleDerivedPermissions(userId)) {
            ids.add(row.getPermissionId());
        }
        return ids;
    }

    /** Returns the override the new state needs, or null when the role already matches. */
    private static String newOverrideType(boolean granted, boolean fromRole) {
        if (granted == fromRole) {
            return null;
        }
        return granted ? UserPermissionOverride.TYPE_GRANT : UserPermissionOverride.TYPE_REVOKE;
    }

    /** Returns the override type only while the row is still in effect. */
    private static String activeTypeOf(Optional<UserPermissionOverride> existing) {
        LocalDateTime now = LocalDateTime.now();
        return existing.filter(o -> o.isInEffect(now))
                .map(UserPermissionOverride::getOverrideType)
                .orElse(null);
    }

    /** Renders a permission state as the Vietnamese label the history tab shows. */
    private static String describe(boolean fromRole, String overrideType) {
        if (UserPermissionOverride.TYPE_REVOKE.equals(overrideType)) {
            return "thu hồi";
        }
        if (UserPermissionOverride.TYPE_GRANT.equals(overrideType)) {
            return "cấp thêm";
        }
        return fromRole ? "theo vai trò" : "không có";
    }
}
