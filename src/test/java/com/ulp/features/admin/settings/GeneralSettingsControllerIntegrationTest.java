package com.ulp.features.admin.settings;

import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@code /admin/settings/general} — full context + real DB.
 * Covers:
 * <ul>
 *   <li>Auth guards (anonymous, STUDENT, LECTURER, HEAD, ADMIN)</li>
 *   <li>Form render with stored values</li>
 *   <li>Save valid settings — redirect with success</li>
 *   <li>Save blank optional fields — accepted, stored verbatim</li>
 *   <li>Save empty required siteName — re-render form with field error</li>
 *   <li>Save invalid contact email — re-render form with field error</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeneralSettingsControllerIntegrationTest {

    private static final String GROUP = "GENERAL";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SystemSettingsRepository repository;

    /** Backup all GENERAL group values to restore after each test. */
    private Map<String, String> backupRows;

    @BeforeEach
    void setUp() {
        backupRows = new HashMap<>();
        for (SystemSetting s : repository.findBySettingGroup(GROUP)) {
            backupRows.put(s.getSettingKey(), s.getSettingValue());
        }
    }

    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : backupRows.entrySet()) {
            repository.findBySettingKey(entry.getKey()).ifPresent(s -> {
                s.setSettingValue(entry.getValue());
                repository.save(s);
            });
        }
    }

    // ─────────────────── Auth guards ───────────────────

    @Test
    @WithAnonymousUser
    void anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_forbidden() throws Exception {
        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_forbidden() throws Exception {
        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_forbidden() throws Exception {
        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isForbidden());
    }

    // ─────────────────── Form render ───────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_form_renders_with_stored_values() throws Exception {
        repository.findBySettingKey("site.name").ifPresent(s -> {
            s.setSettingValue("My Platform");
            repository.save(s);
        });

        mockMvc.perform(get("/admin/settings/general"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cài đặt chung")))
                .andExpect(content().string(containsString("My Platform")));
    }

    // ─────────────────── Save (POST) ───────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void save_valid_settings_redirects_with_success() throws Exception {
        mockMvc.perform(post("/admin/settings/general")
                        .with(csrf())
                        .param("siteName", "ULP New Name")
                        .param("siteDescription", "A new description")
                        .param("siteLogoUrl", "/images/new-logo.png")
                        .param("siteContactEmail", "hello@ulp.edu.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/settings/general"));

        assertThat(repository.findBySettingKey("site.name"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo("ULP New Name");
        assertThat(repository.findBySettingKey("site.contact_email"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo("hello@ulp.edu.vn");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void save_blank_optional_fields_are_accepted_and_stored() throws Exception {
        // Optional logo/description/contact may be cleared intentionally.
        mockMvc.perform(post("/admin/settings/general")
                        .with(csrf())
                        .param("siteName", "Only Name")
                        .param("siteDescription", "")
                        .param("siteLogoUrl", "")
                        .param("siteContactEmail", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/settings/general"));

        assertThat(repository.findBySettingKey("site.logo_url"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo("");
        assertThat(repository.findBySettingKey("site.contact_email"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo("");
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void save_empty_site_name_renders_form_with_error() throws Exception {
        mockMvc.perform(post("/admin/settings/general")
                        .with(csrf())
                        .param("siteName", "")
                        .param("siteDescription", "d")
                        .param("siteLogoUrl", "/l.png")
                        .param("siteContactEmail", "a@b.com"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tên hệ thống là bắt buộc")));

        // No row should be updated — site.name still the seed value.
        assertThat(repository.findBySettingKey("site.name"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo(backupRows.get("site.name"));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void save_invalid_contact_email_renders_form_with_error() throws Exception {
        mockMvc.perform(post("/admin/settings/general")
                        .with(csrf())
                        .param("siteName", "ULP")
                        .param("siteDescription", "d")
                        .param("siteLogoUrl", "/l.png")
                        .param("siteContactEmail", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Email liên hệ không hợp lệ")));

        assertThat(repository.findBySettingKey("site.name"))
                .get().extracting(SystemSetting::getSettingValue)
                .isEqualTo(backupRows.get("site.name"));
    }
}
