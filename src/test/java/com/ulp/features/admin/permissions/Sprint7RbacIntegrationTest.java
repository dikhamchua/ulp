package com.ulp.features.admin.permissions;

import com.ulp.config.CacheConfig;
import com.ulp.entities.Permission;
import com.ulp.entities.User;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRepository;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.ulp.common.IConstant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Sprint 7 integration test for the two RBAC admin screens.
 *
 * <p>Covers the requirement scenarios for {@code /admin/permissions} and
 * {@code /admin/permissions/overrides}: ADMIN-only access, matrix attach and detach,
 * core-permission rejection, override create / replace / deactivate, and reason
 * validation surfacing inline rather than as a toast.
 *
 * <p>Assertions go through MockMvc rather than the services directly, so the
 * {@code @PreAuthorize} gate, the flash-attribute contract the templates read, and the
 * redirect targets are all exercised as the browser would hit them.
 *
 * <p>Every test is {@code @Transactional} and therefore rolled back, so no matrix row or
 * override row survives the run.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}:
 * {@code admin@ulp.edu.vn}, {@code lecturer@ulp.edu.vn}, {@code head@ulp.edu.vn},
 * {@code student@ulp.edu.vn}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint7RbacIntegrationTest {

    /** Non-core permission used for the attach/detach cases; safe to toggle. */
    private static final String TOGGLEABLE_KEY = "library.manage";

    /**
     * The permission guarding both screens.
     *
     * <p>Spelled out rather than referenced from {@code AdminPermissionsGuard}, whose
     * constant is package-private: this is the same literal the controllers'
     * {@code @PreAuthorize} hardcodes, so pinning it here catches a drift between the
     * guard and the annotation.
     */
    private static final String SCREENS_KEY = "system.permissions";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private CacheManager cacheManager;

    /**
     * Clears the permission cache between tests.
     *
     * <p>The cache is a non-transactional singleton, so an entry primed by one test
     * survives that test's rollback and would leak a stale permission set into the next.
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

    /** Reads the single override row for a user + permission pair, if any. */
    private Optional<UserPermissionOverride> overrideFor(Long userId, String featureKey) {
        return overrideRepository.findByUserIdAndPermissionId(userId, permission(featureKey).getId());
    }

    // ───────────────────── access control ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_the_matrix_screen() throws Exception {
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADMIN_PERMISSIONS_ROLES))
                .andExpect(model().attributeExists(ATTR_PERMISSION_MATRIX))
                .andExpect(model().attribute(ATTR_ACTIVE_TAB, TAB_PERMISSIONS));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_the_override_screen() throws Exception {
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS_OVERRIDES))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_ADMIN_PERMISSIONS_OVERRIDES))
                .andExpect(model().attributeExists(ATTR_PERMISSION_OVERRIDES))
                .andExpect(model().attributeExists(ATTR_PERMISSION_CATALOG))
                .andExpect(model().attributeExists(ATTR_OVERRIDE_USERS));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void a_lecturer_is_refused_both_screens() throws Exception {
        // The /admin/** matcher rejects before PERM_system.permissions is even consulted.
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS)).andExpect(status().isForbidden());
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS_OVERRIDES)).andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void a_student_is_refused_both_screens() throws Exception {
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS)).andExpect(status().isForbidden());
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS_OVERRIDES)).andExpect(status().isForbidden());
    }

    @Test
    void an_anonymous_visitor_is_redirected_to_login() throws Exception {
        mockMvc.perform(get(URL_ADMIN_PERMISSIONS))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ───────────────────── matrix attach / detach ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void attaching_a_permission_creates_the_role_grant() throws Exception {
        Permission target = permission(TOGGLEABLE_KEY);
        // Start from a known-detached state so the assertion cannot pass by accident.
        rolePermissionRepository.findByIdRoleCodeAndIdPermissionId(Role.STUDENT.name(), target.getId())
                .ifPresent(rolePermissionRepository::delete);

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS).with(csrf())
                        .param("roleCode", Role.STUDENT.name())
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("granted", "true"))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_PERMISSION_ATTACHED));

        assertThat(rolePermissionRepository
                .existsByIdRoleCodeAndIdPermissionId(Role.STUDENT.name(), target.getId()))
                .as("the grant row must exist after attaching")
                .isTrue();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void detaching_a_non_core_permission_removes_the_role_grant() throws Exception {
        Permission target = permission(TOGGLEABLE_KEY);
        // Attach first so there is something to remove.
        mockMvc.perform(post(URL_ADMIN_PERMISSIONS).with(csrf())
                .param("roleCode", Role.STUDENT.name())
                .param("featureKey", TOGGLEABLE_KEY)
                .param("granted", "true"));

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS).with(csrf())
                        .param("roleCode", Role.STUDENT.name())
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("granted", "false"))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_PERMISSION_DETACHED));

        assertThat(rolePermissionRepository
                .existsByIdRoleCodeAndIdPermissionId(Role.STUDENT.name(), target.getId()))
                .as("the grant row must be gone after detaching")
                .isFalse();
    }

    // ───────────────────── core-permission rejection ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void detaching_the_permission_screens_key_from_admin_is_rejected() throws Exception {
        // The self-lockout guard: without this, an admin could remove their own access
        // to the very screen that hands it back.
        Permission core = permission(SCREENS_KEY);

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS).with(csrf())
                        .param("roleCode", Role.ADMIN.name())
                        .param("featureKey", SCREENS_KEY)
                        .param("granted", "false"))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS))
                .andExpect(flash().attributeExists(ATTR_FLASH_ERROR));

        assertThat(rolePermissionRepository
                .existsByIdRoleCodeAndIdPermissionId(Role.ADMIN.name(), core.getId()))
                .as("a rejected detach must leave the grant row untouched")
                .isTrue();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void a_rejected_detach_reports_the_core_permission_reason() throws Exception {
        mockMvc.perform(post(URL_ADMIN_PERMISSIONS).with(csrf())
                        .param("roleCode", Role.ADMIN.name())
                        .param("featureKey", SCREENS_KEY)
                        .param("granted", "false"))
                .andExpect(flash().attribute(ATTR_FLASH_ERROR,
                        containsString("quyền cốt lõi")))
                // A rejection must not also claim success.
                .andExpect(flash().attributeCount(1));
    }

    // ───────────────────── overrides ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void creating_a_grant_override_persists_an_active_row() throws Exception {
        User student = user("student@ulp.edu.vn");

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                        .param("userId", String.valueOf(student.getId()))
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("overrideType", UserPermissionOverride.TYPE_GRANT)
                        .param("reason", "Hỗ trợ tạm thời"))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS_OVERRIDES))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_SAVED));

        UserPermissionOverride saved = overrideFor(student.getId(), TOGGLEABLE_KEY).orElseThrow();
        assertThat(saved.getOverrideType()).isEqualTo(UserPermissionOverride.TYPE_GRANT);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getReason()).isEqualTo("Hỗ trợ tạm thời");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void resubmitting_the_same_pair_replaces_the_row_instead_of_duplicating_it() throws Exception {
        User student = user("student@ulp.edu.vn");
        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                .param("userId", String.valueOf(student.getId()))
                .param("featureKey", TOGGLEABLE_KEY)
                .param("overrideType", UserPermissionOverride.TYPE_GRANT)
                .param("reason", "lần đầu"));
        Long firstId = overrideFor(student.getId(), TOGGLEABLE_KEY).orElseThrow().getId();

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                        .param("userId", String.valueOf(student.getId()))
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("overrideType", UserPermissionOverride.TYPE_REVOKE)
                        .param("reason", "đổi ý"))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_SAVED));

        UserPermissionOverride replaced = overrideFor(student.getId(), TOGGLEABLE_KEY).orElseThrow();
        assertThat(replaced.getId())
                .as("the unique index means the row is updated in place, not re-inserted")
                .isEqualTo(firstId);
        assertThat(replaced.getOverrideType()).isEqualTo(UserPermissionOverride.TYPE_REVOKE);
        assertThat(replaced.getReason()).isEqualTo("đổi ý");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void an_expiry_submitted_by_the_form_is_stored() throws Exception {
        User student = user("student@ulp.edu.vn");
        LocalDateTime expiry = LocalDateTime.now().plusDays(3).withNano(0);

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                        .param("userId", String.valueOf(student.getId()))
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("overrideType", UserPermissionOverride.TYPE_GRANT)
                        .param("reason", "tạm thời")
                        .param("expiresAt", expiry.toString()))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_SAVED));

        assertThat(overrideFor(student.getId(), TOGGLEABLE_KEY).orElseThrow().getExpiresAt())
                .isEqualTo(expiry);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void deactivating_an_override_keeps_the_row_as_history() throws Exception {
        User student = user("student@ulp.edu.vn");
        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                .param("userId", String.valueOf(student.getId()))
                .param("featureKey", TOGGLEABLE_KEY)
                .param("overrideType", UserPermissionOverride.TYPE_GRANT)
                .param("reason", "tạm thời"));
        Long overrideId = overrideFor(student.getId(), TOGGLEABLE_KEY).orElseThrow().getId();

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES + "/" + overrideId + "/deactivate")
                        .with(csrf()))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS_OVERRIDES))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_OVERRIDE_DEACTIVATED));

        UserPermissionOverride row = overrideRepository.findById(overrideId).orElseThrow();
        assertThat(row.isActive()).as("deactivation must not delete the row").isFalse();
    }

    // ───────────────────── reason validation ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void a_blank_reason_is_reported_inline_and_writes_no_row() throws Exception {
        User student = user("student@ulp.edu.vn");

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                        .param("userId", String.valueOf(student.getId()))
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("overrideType", UserPermissionOverride.TYPE_GRANT)
                        .param("reason", "   "))
                .andExpect(redirectedUrl(URL_ADMIN_PERMISSIONS_OVERRIDES))
                // Field-level errors stay inline, so no success toast may be flashed.
                .andExpect(flash().attributeExists(ATTR_FLASH_REASON_ERROR))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, nullValue()))
                // The rejected submission is flashed back so the form is not blanked.
                .andExpect(flash().attributeExists(ATTR_OVERRIDE_FORM));

        assertThat(overrideFor(student.getId(), TOGGLEABLE_KEY))
                .as("a rejected submission must not persist an override")
                .isEmpty();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void an_unknown_override_type_is_rejected_inline() throws Exception {
        User student = user("student@ulp.edu.vn");

        mockMvc.perform(post(URL_ADMIN_PERMISSIONS_OVERRIDES).with(csrf())
                        .param("userId", String.valueOf(student.getId()))
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("overrideType", "ESCALATE")
                        .param("reason", "thử nghiệm"))
                .andExpect(flash().attributeExists(ATTR_FLASH_REASON_ERROR));

        assertThat(overrideFor(student.getId(), TOGGLEABLE_KEY)).isEmpty();
    }

    // ───────────────────── CSRF ─────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void a_post_without_a_csrf_token_is_refused() throws Exception {
        mockMvc.perform(post(URL_ADMIN_PERMISSIONS)
                        .param("roleCode", Role.STUDENT.name())
                        .param("featureKey", TOGGLEABLE_KEY)
                        .param("granted", "true"))
                .andExpect(status().isForbidden());
    }
}
