package com.ulp.features.admin.permissions.service;

import com.ulp.entities.Permission;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link AdminPermissionsGuard}.
 *
 * <p>Covers the self-lockout protection:
 * <ul>
 *   <li>core permissions cannot be detached from ADMIN</li>
 *   <li>the same permissions are freely detachable from every non-ADMIN role</li>
 *   <li>non-core permissions are detachable even from ADMIN</li>
 * </ul>
 */
class AdminPermissionsGuardTest {

    private final AdminPermissionsGuard guard = new AdminPermissionsGuard();

    /** Builds a catalogue row without touching the database. */
    private static Permission permission(String featureKey, String group) {
        // The entity's no-arg constructor is protected for JPA, so reach it reflectively.
        Permission permission = BeanUtils.instantiateClass(Permission.class);
        ReflectionTestUtils.setField(permission, "featureKey", featureKey);
        ReflectionTestUtils.setField(permission, "permissionGroup", group);
        return permission;
    }

    // ───────────────────── ADMIN is protected ─────────────────────

    @Test
    void rejects_detaching_a_system_group_permission_from_admin() {
        Permission core = permission("system.settings", "SYSTEM");

        assertThatThrownBy(() -> guard.checkDetachAllowed(Role.ADMIN.name(), core))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("quyền cốt lõi");
    }

    @Test
    void rejects_detaching_a_user_manage_permission_from_admin() {
        Permission core = permission("user.create", "USER_MANAGE");

        assertThatThrownBy(() -> guard.checkDetachAllowed(Role.ADMIN.name(), core))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejects_detaching_the_permission_guarding_the_permission_screens() {
        // Guarded by feature key regardless of the group it is catalogued under.
        Permission screens = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY, "OTHER");

        assertThatThrownBy(() -> guard.checkDetachAllowed(Role.ADMIN.name(), screens))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void allows_detaching_a_non_core_permission_from_admin() {
        Permission ordinary = permission("library.manage", "LIBRARY");

        assertThatCode(() -> guard.checkDetachAllowed(Role.ADMIN.name(), ordinary))
                .doesNotThrowAnyException();
    }

    // ───────────────────── non-ADMIN roles are unaffected ─────────────────────

    @Test
    void allows_detaching_core_permissions_from_every_non_admin_role() {
        Permission core = permission("system.settings", "SYSTEM");

        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }
            assertThatCode(() -> guard.checkDetachAllowed(role.name(), core))
                    .as("core permissions are only frozen on ADMIN, not on %s", role)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void core_flag_is_false_for_non_admin_roles() {
        assertThat(guard.isCoreAdminPermission(Role.HEAD.name(), "SYSTEM", "system.settings"))
                .isFalse();
        assertThat(guard.isCoreAdminPermission(Role.ADMIN.name(), "SYSTEM", "system.settings"))
                .isTrue();
    }

    @Test
    void core_flag_is_false_for_ordinary_admin_permissions() {
        assertThat(guard.isCoreAdminPermission(Role.ADMIN.name(), "LIBRARY", "library.manage"))
                .isFalse();
    }

    // ───────────────────── defensive input ─────────────────────

    @Test
    void tolerates_a_null_permission() {
        // An unknown feature key resolves to null upstream; the guard must not NPE.
        assertThatCode(() -> guard.checkDetachAllowed(Role.ADMIN.name(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void tolerates_an_unknown_role_code() {
        Permission core = permission("system.settings", "SYSTEM");

        assertThatCode(() -> guard.checkDetachAllowed("NOT_A_ROLE", core))
                .doesNotThrowAnyException();
    }
}
