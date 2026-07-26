package com.ulp;

import com.ulp.entities.AiSystemPrompt;
import com.ulp.features.admin.settings.dto.AiSystemPromptDtos.AiSystemPromptForm;
import com.ulp.features.admin.settings.repository.AiSystemPromptRepository;
import com.ulp.features.admin.settings.service.AiSystemPromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.ulp.common.IConstant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Sprint 8 integration test for the AI system prompt catalog.
 *
 * <p>Covers CRUD through MockMvc — so the {@code system.ai} permission gate, the flash
 * contract the template reads, and the redirect targets are exercised exactly as a
 * browser would hit them — plus the permission gate for a non-admin role.
 *
 * <p>Also pins the routing hazard: {@code AiSettingsController} is mapped at
 * {@code /admin/settings/ai} and already declares {@code /{id}/edit}, so
 * {@code /admin/settings/ai/prompts} would be swallowed by it if the literal segment
 * ever stopped winning. Every route here asserts the prompt view, which fails loudly
 * if the provider controller takes the request instead.
 *
 * <p>Every test is {@code @Transactional} and rolled back, so no prompt row survives —
 * including the {@code @BeforeEach} that empties the table. This feature writes nothing
 * outside the test transaction (no {@code REQUIRES_NEW} logging), so unlike
 * {@code Sprint8AiSettingsIntegrationTest} no {@code @AfterEach} cleanup is required.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}:
 * {@code admin@ulp.edu.vn}, {@code lecturer@ulp.edu.vn}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint8AiSystemPromptIntegrationTest {

    private static final String BODY = "Bạn là trợ giảng của một trường đại học.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiSystemPromptRepository repository;

    @Autowired
    private AiSystemPromptService service;

    /**
     * Clears {@code ai_system_prompts} so every test starts from a known-empty table.
     *
     * <p>These tests assert on absolute state ({@code repository.count()},
     * {@code singleElement()}), so any row an operator created through the UI would break
     * them. The delete runs inside the test transaction and is rolled back with it, so
     * real rows are restored when the test ends.
     */
    @BeforeEach
    void clearPrompts() {
        repository.deleteAll();
        repository.flush();
    }

    // ───────── Access control ────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_the_prompt_catalog() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI_PROMPTS))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_PROMPTS))
                .andExpect(model().attributeExists(ATTR_AI_PROMPTS, ATTR_FORM));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void a_user_without_the_ai_permission_is_forbidden() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI_PROMPTS))
                .andExpect(status().isForbidden());
    }

    // ───────── Create ────────────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_persists_the_prompt_and_flashes_success() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("name", "Trợ giảng")
                        .param("description", "Dùng cho màn hình hỏi đáp")
                        .param("content", BODY)
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(URL_SETTINGS_AI_PROMPTS))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_CREATED));

        AiSystemPrompt saved = repository.findByName("Trợ giảng").orElseThrow();
        assertThat(saved.getContent()).isEqualTo(BODY);
        assertThat(saved.getDescription()).isEqualTo("Dùng cho màn hình hỏi đáp");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void a_blank_description_is_stored_as_null_rather_than_an_empty_string() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("name", "Không mô tả")
                        .param("description", "   ")
                        .param("content", BODY)
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findByName("Không mô tả").orElseThrow().getDescription()).isNull();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void duplicate_name_is_an_inline_field_error_not_a_flash() throws Exception {
        persist("Trùng tên");

        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("name", "Trùng tên")
                        .param("description", "")
                        .param("content", BODY)
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_PROMPTS))
                .andExpect(model().attributeHasFieldErrors(ATTR_FORM, "name"))
                // A field problem must not surface as a toast.
                .andExpect(flash().attributeCount(0));

        // The second row was rejected before the unique key could fire.
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void blank_content_reports_an_inline_field_error() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("name", "Rỗng nội dung")
                        .param("description", "")
                        .param("content", "   ")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_PROMPTS))
                .andExpect(model().attributeHasFieldErrors(ATTR_FORM, "content"));

        assertThat(repository.findByName("Rỗng nội dung")).isEmpty();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void blank_name_reports_an_inline_field_error() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("name", "")
                        .param("description", "")
                        .param("content", BODY)
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_PROMPTS))
                .andExpect(model().attributeHasFieldErrors(ATTR_FORM, "name"));

        assertThat(repository.count()).isZero();
    }

    // ───────── Edit / update ─────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_loads_the_full_body_into_the_panel() throws Exception {
        AiSystemPrompt prompt = persist("Sửa tôi");

        mockMvc.perform(get(URL_SETTINGS_AI_PROMPTS + "/" + prompt.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_PROMPTS))
                .andExpect(model().attribute(ATTR_FORM,
                        new AiSystemPromptForm(prompt.getId(), "Sửa tôi", "Mô tả gốc", BODY, true)));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void edit_of_a_missing_prompt_redirects_with_an_error_flash() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI_PROMPTS + "/999999/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(URL_SETTINGS_AI_PROMPTS))
                .andExpect(flash().attribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void update_changes_the_stored_row() throws Exception {
        AiSystemPrompt prompt = persist("Bản cũ");

        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("id", String.valueOf(prompt.getId()))
                        .param("name", "Bản mới")
                        .param("description", "Mô tả mới")
                        .param("content", "Nội dung đã thay đổi")
                        .param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_UPDATED));

        AiSystemPrompt reloaded = repository.findById(prompt.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Bản mới");
        assertThat(reloaded.getDescription()).isEqualTo("Mô tả mới");
        assertThat(reloaded.getContent()).isEqualTo("Nội dung đã thay đổi");
        assertThat(reloaded.isEnabled()).isFalse();
        // Renaming must not create a second row.
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void keeping_its_own_name_on_edit_is_not_treated_as_a_duplicate() throws Exception {
        AiSystemPrompt prompt = persist("Giữ tên");

        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS).with(csrf())
                        .param("id", String.valueOf(prompt.getId()))
                        .param("name", "Giữ tên")
                        .param("description", "Mô tả gốc")
                        .param("content", "Nội dung mới")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_UPDATED));

        assertThat(repository.findById(prompt.getId()).orElseThrow().getContent())
                .isEqualTo("Nội dung mới");
    }

    // ───────── Toggle / delete ───────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_flips_the_enabled_flag() throws Exception {
        AiSystemPrompt prompt = persist("Bật tắt");

        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS + "/" + prompt.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_DISABLED));

        assertThat(repository.findById(prompt.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_removes_the_row() throws Exception {
        AiSystemPrompt prompt = persist("Xoá tôi");

        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS + "/" + prompt.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROMPT_DELETED));

        assertThat(repository.findById(prompt.getId())).isEmpty();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_of_a_missing_prompt_reports_an_error_flash() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI_PROMPTS + "/999999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_ERROR, MSG_AI_PROMPT_NOT_FOUND));
    }

    // ───────── Service-level read model ──────────────────────────────

    @Test
    void a_long_body_is_truncated_into_a_single_line_preview() {
        AiSystemPrompt prompt = new AiSystemPrompt("Dài",
                null, "x".repeat(200) + "\ndòng hai");
        repository.save(prompt);

        assertThat(service.listRows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.contentPreview()).hasSize(81).endsWith("…");
                    assertThat(row.contentPreview()).doesNotContain("\n");
                    assertThat(row.index()).isEqualTo(1);
                });
    }

    @Test
    void a_short_body_is_previewed_verbatim_without_an_ellipsis() {
        persist("Ngắn");

        assertThat(service.listRows())
                .singleElement()
                .satisfies(row -> assertThat(row.contentPreview()).isEqualTo(BODY));
    }

    @Test
    void load_form_round_trips_the_stored_values() {
        AiSystemPrompt prompt = persist("Vòng lặp");

        Optional<AiSystemPromptForm> form = service.loadForm(prompt.getId());
        assertThat(form).isPresent();
        assertThat(form.get().content()).isEqualTo(BODY);
        assertThat(form.get().name()).isEqualTo("Vòng lặp");
    }

    @Test
    void rows_are_listed_alphabetically_by_name() {
        persist("Zulu");
        persist("Alpha");

        assertThat(service.listRows())
                .extracting(row -> row.name())
                .containsExactly("Alpha", "Zulu");
    }

    // ─────────────────────────────────────────────────────────────────

    /** Saves an enabled prompt carrying the shared fixture body. */
    private AiSystemPrompt persist(String name) {
        return repository.save(new AiSystemPrompt(name, "Mô tả gốc", BODY));
    }
}
