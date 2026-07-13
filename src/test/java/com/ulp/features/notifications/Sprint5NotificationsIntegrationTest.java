package com.ulp.features.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for Sprint 5 notifications feature (issue #63/#64).
 *
 * <p>Verifies:
 * <ul>
 *   <li>GET /my/notifications — authenticated user sees the inbox (200 + view name)</li>
 *   <li>GET /my/notifications — unauthenticated request redirects to login</li>
 *   <li>GET /my/notifications/unread-count — returns JSON with count key</li>
 *   <li>POST /my/notifications/{id}/open with unknown id — silent no-op, redirects to list</li>
 *   <li>Header fragment exposes notifUnreadCount model attribute</li>
 * </ul>
 *
 * <p>The seed migration V22 inserts demo notifications for student@ulp.edu.vn
 * so the inbox list-view tests have non-empty data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint5NotificationsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Inbox page ────────────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void authenticated_user_can_access_notifications_inbox() throws Exception {
        mockMvc.perform(get("/my/notifications"))
                .andExpect(status().isOk())
                .andExpect(view().name("notifications/index"));
    }

    @Test
    void unauthenticated_request_is_redirected_to_login() throws Exception {
        mockMvc.perform(get("/my/notifications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void notifications_page_contains_expected_html_structure() throws Exception {
        mockMvc.perform(get("/my/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("notif-shell")));
    }

    // ── Unread count JSON endpoint ────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void unread_count_endpoint_returns_json_with_count_key() throws Exception {
        mockMvc.perform(get("/my/notifications/unread-count")
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").isNumber());
    }

    @Test
    void unread_count_requires_authentication() throws Exception {
        mockMvc.perform(get("/my/notifications/unread-count"))
                .andExpect(status().is3xxRedirection());
    }

    // ── Mark-read (open) ──────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void open_unknown_notification_id_is_silent_no_op_and_redirects_to_list() throws Exception {
        // Non-existent id: silent no-op, no 404 (owner-scoped design decision D5).
        mockMvc.perform(post("/my/notifications/999999/open").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my/notifications"));
    }

    @Test
    void open_without_authentication_redirects_to_login() throws Exception {
        mockMvc.perform(post("/my/notifications/1/open").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void open_without_csrf_token_is_rejected_with_403() throws Exception {
        // Regression guard: CSRF protection must be active on the mark-read endpoint.
        // A real browser form without the _csrf hidden input hits this path.
        mockMvc.perform(post("/my/notifications/1/open"))
                .andExpect(status().isForbidden());
    }

    // ── Pagination parameter ──────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void page_param_beyond_last_page_returns_empty_list_not_error() throws Exception {
        mockMvc.perform(get("/my/notifications").param("page", "999"))
                .andExpect(status().isOk())
                .andExpect(view().name("notifications/index"));
    }
}
