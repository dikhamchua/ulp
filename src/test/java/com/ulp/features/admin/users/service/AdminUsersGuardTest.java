package com.ulp.features.admin.users.service;

import com.ulp.security.Role;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUsersGuard}.
 *
 * <p>The guard is intentionally pure (no transactions, no entity mutations)
 * so these tests run without a Spring context. All branches of the four
 * guard methods are covered, with boundary cases at the
 * "last active admin" threshold.
 */
class AdminUsersGuardTest {

    private UserRepository userRepository;
    private AdminUsersGuard guard;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        guard = new AdminUsersGuard(userRepository);
    }

    // ── requireNotSelf ─────────────────────────────────────────────

    @Test
    void requireNotSelf_throws_when_actorAndTargetMatch() {
        assertThatThrownBy(() -> guard.requireNotSelf(7L, 7L, "deactivate"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("deactivate");
    }

    @Test
    void requireNotSelf_passes_when_different_ids() {
        assertThatCode(() -> guard.requireNotSelf(1L, 2L, "lock"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotSelf_passes_when_actor_is_null() {
        // Defensive: a null actorId should not match anything.
        assertThatCode(() -> guard.requireNotSelf(null, 7L, "delete"))
                .doesNotThrowAnyException();
    }

    // ── requireNotLastActiveAdmin ──────────────────────────────────

    @Test
    void requireNotLastActiveAdmin_passes_when_target_is_not_admin() {
        User lecturer = userOf(10L, Role.LECTURER, true, false);
        // No count call needed; method short-circuits.
        assertThatCode(() -> guard.requireNotLastActiveAdmin(lecturer, "deactivate"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotLastActiveAdmin_passes_when_target_is_inactive() {
        User inactiveAdmin = userOf(11L, Role.ADMIN, false, false);
        assertThatCode(() -> guard.requireNotLastActiveAdmin(inactiveAdmin, "delete"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotLastActiveAdmin_passes_when_target_is_deleted() {
        User deletedAdmin = userOf(12L, Role.ADMIN, true, true);
        assertThatCode(() -> guard.requireNotLastActiveAdmin(deletedAdmin, "delete"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotLastActiveAdmin_passes_when_more_than_one_active_admin() {
        User admin = userOf(13L, Role.ADMIN, true, false);
        when(userRepository.countActiveAdmins("ADMIN")).thenReturn(2L);

        assertThatCode(() -> guard.requireNotLastActiveAdmin(admin, "deactivate"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireNotLastActiveAdmin_throws_when_only_one_active_admin_remains() {
        User lastAdmin = userOf(14L, Role.ADMIN, true, false);
        when(userRepository.countActiveAdmins("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> guard.requireNotLastActiveAdmin(lastAdmin, "deactivate"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("cuối cùng");
    }

    @Test
    void requireNotLastActiveAdmin_throws_when_count_returns_zero() {
        // Defensive: should never happen in practice, but the boundary is <= 1.
        User admin = userOf(15L, Role.ADMIN, true, false);
        when(userRepository.countActiveAdmins("ADMIN")).thenReturn(0L);

        assertThatThrownBy(() -> guard.requireNotLastActiveAdmin(admin, "delete"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── requireRoleNotDemotingLastAdmin ────────────────────────────

    @Test
    void requireRoleNotDemotingLastAdmin_passes_when_target_is_not_admin() {
        User lecturer = userOf(20L, Role.LECTURER, true, false);
        assertThatCode(() -> guard.requireRoleNotDemotingLastAdmin(lecturer, Role.STUDENT))
                .doesNotThrowAnyException();
    }

    @Test
    void requireRoleNotDemotingLastAdmin_passes_when_newRole_is_still_admin() {
        // Editing the form without changing the role away from ADMIN cannot
        // possibly reduce the admin pool, so no count check is needed.
        User admin = userOf(21L, Role.ADMIN, true, false);
        assertThatCode(() -> guard.requireRoleNotDemotingLastAdmin(admin, Role.ADMIN))
                .doesNotThrowAnyException();
    }

    @Test
    void requireRoleNotDemotingLastAdmin_passes_when_more_than_one_active_admin() {
        User admin = userOf(22L, Role.ADMIN, true, false);
        when(userRepository.countActiveAdmins("ADMIN")).thenReturn(3L);

        assertThatCode(() -> guard.requireRoleNotDemotingLastAdmin(admin, Role.LECTURER))
                .doesNotThrowAnyException();
    }

    @Test
    void requireRoleNotDemotingLastAdmin_throws_when_demoting_last_active_admin() {
        User lastAdmin = userOf(23L, Role.ADMIN, true, false);
        when(userRepository.countActiveAdmins("ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> guard.requireRoleNotDemotingLastAdmin(lastAdmin, Role.HEAD))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("vai trò");
    }

    @Test
    void requireRoleNotDemotingLastAdmin_passes_when_target_already_deleted() {
        // A soft-deleted admin does not count toward the active pool; demoting
        // them does not change the active count, so this should not throw.
        User deletedAdmin = userOf(24L, Role.ADMIN, true, true);
        assertThatCode(() -> guard.requireRoleNotDemotingLastAdmin(deletedAdmin, Role.LECTURER))
                .doesNotThrowAnyException();
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static User userOf(Long id, Role role, boolean active, boolean deleted) {
        try {
            var ctor = User.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            User u = ctor.newInstance();
            ReflectionTestUtils.setField(u, "id", id);
            ReflectionTestUtils.setField(u, "role", role);
            ReflectionTestUtils.setField(u, "active", active);
            ReflectionTestUtils.setField(u, "deleted", deleted);
            return u;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to instantiate User via reflection", ex);
        }
    }
}
