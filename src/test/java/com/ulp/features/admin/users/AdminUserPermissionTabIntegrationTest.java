package com.ulp.features.admin.users;

import com.ulp.entities.Permission;
import com.ulp.entities.User;
import com.ulp.entities.UserActivity;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.admin.users.dto.UserPermissionDtos.PermissionGroup;
import com.ulp.features.admin.users.dto.UserPermissionDtos.PermissionRow;
import com.ulp.features.admin.users.dto.UserPermissionDtos.UserPermissionView;
import com.ulp.features.admin.users.repository.UserActivityRepository;
import com.ulp.features.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the "Phân quyền" tab on {@code /admin/users/{id}/edit}.
 *
 * <p>Covers the read side (grouped rows with the right source badge), the toggle
 * endpoint in both directions (grant a missing permission, revoke a role-derived
 * one), the return-to-inheritance path, and the audit row that lands on the
 * account's "Lịch sử cập nhật" timeline.
 *
 * <p>Fixtures rely on {@code V36__rbac_permissions_backfill.sql}: LECTURER holds
 * every LIBRARY / UPLOAD / MODERATION permission and does not hold
 * {@code system.permissions}. Each test rolls back so overrides never leak.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserPermissionTabIntegrationTest {

    /** Granted to LECTURER via role_permissions. */
    private static final String ROLE_DERIVED_KEY = "library.view";
    /** Attached to ADMIN only, so a lecturer lacks it. */
    private static final String NOT_GRANTED_KEY = "system.permissions";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private UserActivityRepository activityRepository;

    private User lecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
    }

    // ──────────────── Read side ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void permissionsTab_rendersGroupedRowsWithSourceBadges() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/admin/users/" + lecturer.getId() + "/edit").param("tab", "permissions"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "permissions"))
                .andExpect(content().string(containsString("Phân quyền tài khoản")))
                .andReturn();

        UserPermissionView view =
                (UserPermissionView) result.getModelAndView().getModel().get("permissionView");
        assertThat(view).isNotNull();
        assertThat(view.roleCode()).isEqualTo("LECTURER");
        assertThat(view.groups()).isNotEmpty();

        assertThat(findRow(view, ROLE_DERIVED_KEY))
                .satisfies(row -> {
                    assertThat(row.effective()).isTrue();
                    assertThat(row.fromRole()).isTrue();
                    assertThat(row.source()).isEqualTo("FROM_ROLE");
                });
        assertThat(findRow(view, NOT_GRANTED_KEY))
                .satisfies(row -> {
                    assertThat(row.effective()).isFalse();
                    assertThat(row.source()).isEqualTo("NONE");
                });
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void permissionsTab_reflectsAnExistingGrantOverride() throws Exception {
        Permission target = permissionRepository.findByFeatureKey(NOT_GRANTED_KEY).orElseThrow();
        overrideRepository.save(new UserPermissionOverride(lecturer.getId(), target.getId(),
                UserPermissionOverride.TYPE_GRANT, "seed", 1L, null));

        MvcResult result = mockMvc.perform(
                        get("/admin/users/" + lecturer.getId() + "/edit").param("tab", "permissions"))
                .andExpect(status().isOk())
                .andReturn();

        UserPermissionView view =
                (UserPermissionView) result.getModelAndView().getModel().get("permissionView");
        PermissionRow row = findRow(view, NOT_GRANTED_KEY);
        assertThat(row.source()).isEqualTo("GRANT");
        assertThat(row.effective()).isTrue();
        assertThat(row.overridden()).isTrue();
        assertThat(view.overrideCount()).isEqualTo(1);
    }

    // ──────────────── Toggle: grant ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_grantingAMissingPermission_writesGrantOverrideAndAuditRow() throws Exception {
        mockMvc.perform(post("/admin/users/" + lecturer.getId() + "/permissions")
                        .param("featureKey", NOT_GRANTED_KEY)
                        .param("granted", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/" + lecturer.getId() + "/edit?tab=permissions"));

        Permission target = permissionRepository.findByFeatureKey(NOT_GRANTED_KEY).orElseThrow();
        Optional<UserPermissionOverride> saved =
                overrideRepository.findByUserIdAndPermissionId(lecturer.getId(), target.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().getOverrideType()).isEqualTo(UserPermissionOverride.TYPE_GRANT);
        assertThat(saved.get().isInEffect(LocalDateTime.now())).isTrue();

        UserActivity audit = latestPermissionActivity();
        assertThat(audit.getMessage())
                .contains(NOT_GRANTED_KEY)
                .contains("không có")
                .contains("cấp thêm");
        assertThat(audit.getPerformedBy()).isNotNull();
    }

    // ──────────────── Toggle: revoke ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_untickingARoleDerivedPermission_writesRevokeOverride() throws Exception {
        mockMvc.perform(post("/admin/users/" + lecturer.getId() + "/permissions")
                        .param("featureKey", ROLE_DERIVED_KEY)
                        .param("granted", "false")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Permission target = permissionRepository.findByFeatureKey(ROLE_DERIVED_KEY).orElseThrow();
        UserPermissionOverride saved = overrideRepository
                .findByUserIdAndPermissionId(lecturer.getId(), target.getId()).orElseThrow();
        assertThat(saved.getOverrideType()).isEqualTo(UserPermissionOverride.TYPE_REVOKE);

        assertThat(latestPermissionActivity().getMessage())
                .contains("theo vai trò")
                .contains("thu hồi");
    }

    // ──────────────── Toggle: back to inheritance ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_restoringRoleState_deactivatesTheOverrideInsteadOfDeletingIt() throws Exception {
        Permission target = permissionRepository.findByFeatureKey(ROLE_DERIVED_KEY).orElseThrow();
        overrideRepository.save(new UserPermissionOverride(lecturer.getId(), target.getId(),
                UserPermissionOverride.TYPE_REVOKE, "seed", 1L, null));

        // Ticking it again matches what the role already gives — no override needed.
        mockMvc.perform(post("/admin/users/" + lecturer.getId() + "/permissions")
                        .param("featureKey", ROLE_DERIVED_KEY)
                        .param("granted", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        UserPermissionOverride row = overrideRepository
                .findByUserIdAndPermissionId(lecturer.getId(), target.getId()).orElseThrow();
        assertThat(row.isInEffect(LocalDateTime.now())).isFalse();
    }

    // ──────────────── Guard: core ADMIN rows are frozen ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_coreAdminPermission_isRejectedWithoutWritingAnOverride() throws Exception {
        User admin = userRepository.findByEmailIgnoreCase("admin@ulp.edu.vn").orElseThrow();

        mockMvc.perform(post("/admin/users/" + admin.getId() + "/permissions")
                        .param("featureKey", NOT_GRANTED_KEY)
                        .param("granted", "false")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Permission target = permissionRepository.findByFeatureKey(NOT_GRANTED_KEY).orElseThrow();
        assertThat(overrideRepository.findByUserIdAndPermissionId(admin.getId(), target.getId()))
                .isEmpty();
    }

    // ──────────────── Helpers ────────────────

    /** Finds one row across all groups; fails the test when the key is absent. */
    private static PermissionRow findRow(UserPermissionView view, String featureKey) {
        for (PermissionGroup group : view.groups()) {
            for (PermissionRow row : group.rows()) {
                if (featureKey.equals(row.featureKey())) {
                    return row;
                }
            }
        }
        throw new AssertionError("No permission row for feature key: " + featureKey);
    }

    /** Returns the most recent PERMISSION_CHANGED audit row written in this test. */
    private UserActivity latestPermissionActivity() {
        List<UserActivity> rows = activityRepository.findAll().stream()
                .filter(a -> UserActivity.TYPE_PERMISSION_CHANGED.equals(a.getType()))
                .toList();
        assertThat(rows).isNotEmpty();
        return rows.get(rows.size() - 1);
    }
}
