package com.ulp.admin.users;

import com.ulp.admin.users.entity.UserActivity;
import com.ulp.admin.users.repository.UserActivityRepository;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration test for the redesigned {@code /admin/users/{id}/edit} detail
 * page (change: admin-user-detail-redesign).
 *
 * <p>Covers each scenario in
 * {@code openspec/changes/admin-user-detail-redesign/specs/admin-user-detail-page/spec.md}:
 * default tab, tab routing for activity and history, invalid-tab fallback,
 * pagination across the history tab, empty-state rendering, and the
 * create-mode shape (no tab strip).
 *
 * <p>Test users are seeded by {@code V5__seed_test_users.sql}. Each test runs
 * inside a {@link Transactional} rollback so audit-row inserts do not leak
 * between cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUsersDetailPageIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserActivityRepository activityRepository;

    private User lecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
    }

    // ──────────────── Default tab + info-tab content ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_defaultTab_rendersInfoSectionCards() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users-form"))
                .andExpect(model().attribute("activeDetailTab", "info"))
                .andExpect(content().string(containsString("id=\"emailInput\"")))
                .andExpect(content().string(containsString("detail-card")))
                .andReturn();

        // Edit mode renders the tab strip; the info tab is active.
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("detail-tabs");
        assertThat(body).contains("Thông tin tài khoản");
    }

    // ──────────────── Activity tab placeholder ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_tabActivity_rendersPlaceholder() throws Exception {
        mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit").param("tab", "activity"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "activity"))
                .andExpect(content().string(containsString("Sắp ra mắt")));
    }

    // ──────────────── History tab: pagination first page ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_tabHistory_rendersActivityTable() throws Exception {
        seedAuditRows(lecturer.getId(), 25);

        MvcResult result = mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit")
                        .param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "history"))
                .andExpect(model().attributeExists("activitiesPage"))
                .andReturn();

        Page<?> page = (Page<?>) result.getModelAndView().getModel().get("activitiesPage");
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(20);

        // Newest first: the seed loop creates rows numbered 0..24; row 24 is
        // the most recent and MUST appear in the first page output.
        assertThat(result.getResponse().getContentAsString()).contains("Audit row 24");
    }

    // ──────────────── History tab: pagination second page ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_tabHistory_page1_returnsRemainingRows() throws Exception {
        seedAuditRows(lecturer.getId(), 25);

        MvcResult result = mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit")
                        .param("tab", "history").param("page", "1"))
                .andExpect(status().isOk())
                .andReturn();

        Page<?> page = (Page<?>) result.getModelAndView().getModel().get("activitiesPage");
        assertThat(page.getNumber()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(5);

        // Row 0 is the oldest; it appears on page 1 (the second page).
        assertThat(result.getResponse().getContentAsString()).contains("Audit row 0");
    }

    // ──────────────── Invalid tab fallback ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_invalidTabValue_fallsBackToInfo() throws Exception {
        mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit").param("tab", "garbage"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "info"));
    }

    // ──────────────── Empty history ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_emptyHistory_rendersEmptyState() throws Exception {
        // The seeded lecturer starts with no audit rows; verify the empty
        // state renders without a <table> element.
        long existing = activityRepository.findAll().stream()
                .filter(a -> a.getTargetUserId().equals(lecturer.getId()))
                .count();
        assertThat(existing).isZero();

        MvcResult result = mockMvc.perform(get("/admin/users/" + lecturer.getId() + "/edit")
                        .param("tab", "history"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("Không có hoạt động");
        // No activity-table element present.
        assertThat(body).doesNotContain("class=\"activity-table\"");
    }

    // ──────────────── Create mode has no tab strip ────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void editPage_createMode_rendersWithoutTabs() throws Exception {
        mockMvc.perform(get("/admin/users/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users-form"))
                .andExpect(content().string(not(containsString("detail-tabs"))))
                .andExpect(content().string(containsString("id=\"emailInput\"")));
    }

    // ──────────────── Helpers ────────────────

    /** Seeds {@code count} audit rows for the target user, oldest first. */
    private void seedAuditRows(Long targetUserId, int count) {
        for (int i = 0; i < count; i++) {
            UserActivity row = new UserActivity(
                    targetUserId,
                    UserActivity.TYPE_UPDATED,
                    "Audit row " + i,
                    null,
                    null);
            activityRepository.saveAndFlush(row);
            // Hibernate auto-populates created_at via DB default. Successive
            // saves get monotonically-increasing timestamps; row {count-1} is
            // the newest. Sleep ~1ms to ensure DATETIME granularity does not
            // collapse two inserts into the same instant.
            try { Thread.sleep(2); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
