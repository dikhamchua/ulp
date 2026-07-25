package com.ulp.features.admin.permissions.service;

import com.ulp.entities.Permission;
import com.ulp.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Refuses matrix changes that would lock every administrator out of the system.
 *
 * <p>The role x permission matrix is editable, which means an admin could in principle
 * detach the very permissions that guard user management and the permission screens
 * themselves. Doing so is unrecoverable through the UI: with {@code system.permissions}
 * gone from ADMIN, nobody can reach the screen that would put it back, and with the
 * USER_MANAGE group gone, nobody can promote another account either. This guard makes
 * that state unreachable by rejecting the detach before it is applied.
 *
 * <p>Only the ADMIN role is protected. Detaching the same permissions from STUDENT,
 * LECTURER or HEAD is legitimate — those roles are not the recovery path.
 *
 * <p>Pure component: it inspects its arguments and either returns or throws. It mutates
 * no state, opens no transaction, and touches no repository, so it is safe to call from
 * inside a caller's transaction before any write happens.
 */
@Component
public class AdminPermissionsGuard {

    /**
     * Permission groups frozen for ADMIN.
     *
     * <p>{@code SYSTEM} covers settings, SMTP, OAuth and — via V36 — {@code system.permissions}.
     * {@code USER_MANAGE} covers viewing, creating, editing and role-assigning users.
     */
    static final Set<String> CORE_GROUPS = Set.of("SYSTEM", "USER_MANAGE");

    /**
     * The permission guarding the RBAC screens, pinned by feature key as well as by group.
     *
     * <p>Group membership alone would already protect it, but naming it explicitly means
     * the lockout protection survives the catalogue ever moving it out of SYSTEM.
     */
    static final String PERMISSION_SCREENS_KEY = "system.permissions";

    private static final String MSG_CORE_PERMISSION =
            "Không thể gỡ quyền cốt lõi khỏi vai trò ADMIN. "
                    + "Quyền này cần thiết để quản trị viên không bị khoá khỏi hệ thống.";

    /**
     * Verifies that a permission may be detached from a role.
     *
     * @param roleCode   the role losing the permission
     * @param permission the permission being detached
     * @throws AccessDeniedException if the pair is a core ADMIN permission
     */
    public void checkDetachAllowed(String roleCode, Permission permission) {
        if (permission == null) {
            return;
        }
        if (isCoreAdminPermission(roleCode, permission.getPermissionGroup(), permission.getFeatureKey())) {
            throw new AccessDeniedException(MSG_CORE_PERMISSION);
        }
    }

    /**
     * Reports whether a role x permission pair is a protected core permission.
     *
     * <p>Exposed so the matrix screen can render these cells disabled instead of letting
     * the user discover the restriction only after a rejected save.
     *
     * @param roleCode        the role owning the cell
     * @param permissionGroup the permission's group
     * @param featureKey      the permission's feature key
     * @return {@code true} when the cell must stay attached
     */
    public boolean isCoreAdminPermission(String roleCode, String permissionGroup, String featureKey) {
        if (!Role.ADMIN.name().equals(roleCode)) {
            return false;
        }
        return CORE_GROUPS.contains(permissionGroup) || PERMISSION_SCREENS_KEY.equals(featureKey);
    }
}
