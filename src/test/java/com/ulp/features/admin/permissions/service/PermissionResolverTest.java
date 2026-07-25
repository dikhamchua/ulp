package com.ulp.features.admin.permissions.service;

import com.ulp.config.CacheConfig;
import com.ulp.entities.Permission;
import com.ulp.entities.User;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link PermissionResolver} against the real RBAC tables.
 *
 * <p>Role inheritance is expanded in SQL through {@code role_hierarchy} while override
 * precedence and expiry are applied in Java, so mocking the repository would only prove
 * the mock. These tests write real override rows and read the real schema.
 *
 * <p>The expiry cases matter beyond the happy path: {@code expires_at} is a zoneless
 * {@code LocalDateTime}, so evaluating it against the database server's clock instead
 * of the JVM's silently misjudges expiry when the two sit in different zones.
 *
 * <p>Every test is {@code @Transactional} and therefore rolled back, so no override
 * row survives the run.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}:
 * {@code admin@ulp.edu.vn}, {@code lecturer@ulp.edu.vn}, {@code student@ulp.edu.vn}.
 */
@SpringBootTest
@Transactional
class PermissionResolverTest {

    @Autowired private PermissionResolver resolver;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;

    /**
     * Clears the permission cache between tests.
     *
     * <p>The cache is a singleton in the Spring context and is not transactional, so an
     * entry primed by one test survives the rollback of that test's override rows and
     * would otherwise leak a stale permission set into the next test.
     */
    @BeforeEach
    void clearPermissionCache() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_USER_PERMISSIONS);
        if (cache != null) {
            cache.clear();
        }
    }

    /** Loads a seeded user by email, failing the test when the seed is missing. */
    private User user(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow();
    }

    /** Loads a catalogue entry by feature key, failing the test when V36 has not run. */
    private Permission permission(String featureKey) {
        return permissionRepository.findByFeatureKey(featureKey).orElseThrow();
    }

    /** Writes an override row and drops the user's cached permission set. */
    private void override(Long userId, Long permissionId, String type,
                          LocalDateTime expiresAt, boolean active) {
        UserPermissionOverride row = new UserPermissionOverride(
                userId, permissionId, type, "test", 1L, expiresAt);
        if (!active) {
            row.deactivate();
        }
        overrideRepository.saveAndFlush(row);
        resolver.evictUser(userId);
    }

    // ───────────────────── role inheritance ─────────────────────

    @Test
    void resolves_a_non_empty_permission_set_for_a_seeded_user() {
        Set<String> keys = resolver.resolvePermissions(user("admin@ulp.edu.vn").getId());

        assertThat(keys).isNotEmpty();
    }

    @Test
    void admin_inherits_every_permission_a_student_holds() {
        // role_hierarchy chains STUDENT <- LECTURER <- HEAD <- ADMIN, and the view
        // expands that chain, so ADMIN's set must be a superset of STUDENT's.
        Set<String> studentKeys = resolver.resolvePermissions(user("student@ulp.edu.vn").getId());
        Set<String> adminKeys = resolver.resolvePermissions(user("admin@ulp.edu.vn").getId());

        assertThat(adminKeys).containsAll(studentKeys);
    }

    @Test
    void a_student_does_not_hold_admin_only_permissions() {
        Set<String> studentKeys = resolver.resolvePermissions(user("student@ulp.edu.vn").getId());

        assertThat(studentKeys).doesNotContain(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);
    }

    // ───────────────────── GRANT ─────────────────────

    @Test
    void a_grant_override_adds_a_permission_the_role_does_not_have() {
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);
        assertThat(resolver.resolvePermissions(student.getId())).doesNotContain(adminOnly.getFeatureKey());

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT, null, true);

        assertThat(resolver.resolvePermissions(student.getId()))
                .contains(adminOnly.getFeatureKey());
    }

    // ───────────────────── REVOKE ─────────────────────

    @Test
    void a_revoke_override_removes_a_permission_the_role_grants() {
        User admin = user("admin@ulp.edu.vn");
        Permission held = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);
        assertThat(resolver.resolvePermissions(admin.getId())).contains(held.getFeatureKey());

        override(admin.getId(), held.getId(), UserPermissionOverride.TYPE_REVOKE, null, true);

        assertThat(resolver.resolvePermissions(admin.getId()))
                .doesNotContain(held.getFeatureKey());
    }

    @Test
    void revoke_wins_over_grant_for_the_same_permission() {
        // Start from a GRANT that demonstrably works, then flip the same row to REVOKE.
        // Asserting absence on a key the user never held would pass even if the
        // resolver ignored overrides entirely, so the GRANT step must come first.
        User student = user("student@ulp.edu.vn");
        Permission target = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(student.getId(), target.getId(), UserPermissionOverride.TYPE_GRANT, null, true);
        assertThat(resolver.resolvePermissions(student.getId()))
                .as("precondition: the GRANT override must add the key")
                .contains(target.getFeatureKey());

        // The unique index allows one row per pair, so replace in place.
        UserPermissionOverride row = overrideRepository
                .findByUserIdAndPermissionId(student.getId(), target.getId()).orElseThrow();
        row.replaceWith(UserPermissionOverride.TYPE_REVOKE, "test", 1L, null);
        overrideRepository.saveAndFlush(row);
        resolver.evictUser(student.getId());

        assertThat(resolver.resolvePermissions(student.getId()))
                .as("REVOKE must beat whatever would otherwise grant the key")
                .doesNotContain(target.getFeatureKey());
    }

    // ───────────────────── expiry and deactivation ─────────────────────

    @Test
    void an_expired_grant_override_is_ignored_but_its_row_survives() {
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT,
                LocalDateTime.now().minusDays(1), true);

        assertThat(resolver.resolvePermissions(student.getId()))
                .doesNotContain(adminOnly.getFeatureKey());
        assertThat(overrideRepository.findByUserIdAndPermissionId(student.getId(), adminOnly.getId()))
                .as("an expired override is ignored at read time, never deleted")
                .isPresent();
    }

    @Test
    void an_inactive_grant_override_is_ignored_but_its_row_survives() {
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT, null, false);

        assertThat(resolver.resolvePermissions(student.getId()))
                .doesNotContain(adminOnly.getFeatureKey());
        assertThat(overrideRepository.findByUserIdAndPermissionId(student.getId(), adminOnly.getId()))
                .isPresent();
    }

    @Test
    void a_grant_that_lapsed_moments_ago_no_longer_grants() {
        // The security-critical direction: a temporary elevation must stop the instant it
        // lapses. A one-minute-old expiry is well inside any plausible clock offset
        // between the JVM and the database server, so this fails if expiry is judged
        // against the wrong clock.
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT,
                LocalDateTime.now().minusMinutes(1), true);

        assertThat(resolver.resolvePermissions(student.getId()))
                .as("a lapsed GRANT must not keep elevating the user")
                .doesNotContain(adminOnly.getFeatureKey());
    }

    @Test
    void a_grant_expiring_shortly_is_still_in_effect() {
        // The mirror of the test above: a still-valid short-lived GRANT must apply, so
        // the fix cannot be mistaken for "expire everything early".
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT,
                LocalDateTime.now().plusMinutes(10), true);

        assertThat(resolver.resolvePermissions(student.getId()))
                .contains(adminOnly.getFeatureKey());
    }

    @Test
    void an_expired_revoke_override_stops_suppressing_the_permission() {
        User admin = user("admin@ulp.edu.vn");
        Permission held = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(admin.getId(), held.getId(), UserPermissionOverride.TYPE_REVOKE,
                LocalDateTime.now().minusMinutes(5), true);

        assertThat(resolver.resolvePermissions(admin.getId()))
                .as("a lapsed REVOKE must return the role-granted permission")
                .contains(held.getFeatureKey());
    }

    @Test
    void a_null_expiry_never_expires() {
        User admin = user("admin@ulp.edu.vn");
        Permission held = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(admin.getId(), held.getId(), UserPermissionOverride.TYPE_REVOKE, null, true);

        assertThat(resolver.resolvePermissions(admin.getId()))
                .doesNotContain(held.getFeatureKey());
    }

    @Test
    void a_future_expiry_is_still_in_effect() {
        User admin = user("admin@ulp.edu.vn");
        Permission held = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        override(admin.getId(), held.getId(), UserPermissionOverride.TYPE_REVOKE,
                LocalDateTime.now().plusDays(1), true);

        assertThat(resolver.resolvePermissions(admin.getId()))
                .doesNotContain(held.getFeatureKey());
    }

    // ───────────────────── caching ─────────────────────

    @Test
    void a_null_user_id_resolves_to_an_empty_set() {
        assertThat(resolver.resolvePermissions(null)).isEmpty();
    }

    @Test
    void eviction_makes_the_next_resolution_reflect_new_data() {
        User student = user("student@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);

        // Prime the cache with the pre-change set.
        assertThat(resolver.resolvePermissions(student.getId())).doesNotContain(adminOnly.getFeatureKey());

        override(student.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_GRANT, null, true);

        assertThat(resolver.resolvePermissions(student.getId()))
                .as("evictUser() must drop the stale entry")
                .contains(adminOnly.getFeatureKey());
    }

    @Test
    void evict_role_tolerates_a_blank_or_unknown_role_code() {
        resolver.evictRole(null);
        resolver.evictRole("");
        resolver.evictRole("NOT_A_ROLE");
        // No exception: eviction is best-effort housekeeping, never a failure path.
        assertThat(resolver.resolvePermissions(user("admin@ulp.edu.vn").getId())).isNotEmpty();
    }

    @Test
    void evict_role_clears_users_of_that_role_and_its_descendants() {
        User admin = user("admin@ulp.edu.vn");
        Permission adminOnly = permission(AdminPermissionsGuard.PERMISSION_SCREENS_KEY);
        assertThat(resolver.resolvePermissions(admin.getId())).contains(adminOnly.getFeatureKey());

        UserPermissionOverride row = new UserPermissionOverride(
                admin.getId(), adminOnly.getId(), UserPermissionOverride.TYPE_REVOKE,
                "test", 1L, null);
        overrideRepository.saveAndFlush(row);

        // Evicting STUDENT must cascade to ADMIN, which inherits from it.
        resolver.evictRole(Role.STUDENT.name());

        assertThat(resolver.resolvePermissions(admin.getId()))
                .doesNotContain(adminOnly.getFeatureKey());
    }
}
