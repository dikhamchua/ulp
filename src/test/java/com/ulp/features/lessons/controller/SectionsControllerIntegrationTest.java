package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.SectionDtos.ReorderRequest;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.SectionsService;
import com.ulp.security.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@link SectionsController}.
 *
 * <p>Drives the lessons-tab endpoints through {@link MockMvc} so the
 * security filter chain, content negotiation, and CSRF handling are all
 * exercised end to end.
 *
 * <p>Create / rename go through full-page forms (POST returns 302 +
 * flashSuccess); delete + reorder remain JSON endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SectionsControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private SectionsService sectionsService;

    private User lecturer;
    private User head;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        head = userRepository.findByEmailIgnoreCase("head@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Lessons IT class", lecturer.getId(), "LESIT");
    }

    // ── Page render ─────────────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_lessons_page_returns_view_with_sections_in_model() throws Exception {
        Section s1 = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Section s2 = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 2", (short) 1, lecturer.getId()));

        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons"))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/detail-lessons"))
                .andExpect(model().attributeExists("sections"))
                .andExpect(model().attribute("activeTab", "lessons"))
                .andExpect(content().string(containsString("Chương 1")))
                .andExpect(content().string(containsString("Chương 2")));

        assertThat(s1.getId()).isNotNull();
        assertThat(s2.getId()).isNotNull();
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_lessons_with_section_param_populates_selected_section() throws Exception {
        Section saved = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 5", (short) 0, lecturer.getId()));

        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons")
                        .param("section", String.valueOf(saved.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/detail-lessons"))
                .andExpect(model().attribute("selectedSectionId", saved.getId()))
                .andExpect(model().attributeExists("selectedSection"))
                .andExpect(content().string(containsString("Bài giảng của Chương 5")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_lessons_with_invalid_section_param_falls_back_to_all() throws Exception {
        // Section ID không tồn tại trong class này → silent fallback, không 404.
        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons")
                        .param("section", "999999"))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/detail-lessons"))
                .andExpect(model().attribute("selectedSectionId", (Object) null))
                .andExpect(content().string(containsString("Tất cả bài giảng")));
    }

    // ── Create section — full-page form ────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_section_new_renders_form_for_owner() throws Exception {
        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/section-form"))
                .andExpect(model().attribute("mode", "create"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeExists("clazz"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_section_with_valid_title_redirects_with_flash() throws Exception {
        mockMvc.perform(post("/lecturer/classes/" + clazz.getId() + "/lessons/sections")
                        .with(csrf())
                        .param("title", "Chương mới"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/lecturer/classes/" + clazz.getId() + "/lessons"))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<Section> stored = sectionRepository.findByClassIdOrderByDisplayOrderAsc(clazz.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTitle()).isEqualTo("Chương mới");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_section_with_blank_title_re_renders_form_with_error() throws Exception {
        mockMvc.perform(post("/lecturer/classes/" + clazz.getId() + "/lessons/sections")
                        .with(csrf())
                        .param("title", "   "))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/section-form"))
                .andExpect(model().attributeHasFieldErrors("form", "title"));

        assertThat(sectionRepository.findByClassIdOrderByDisplayOrderAsc(clazz.getId()))
                .isEmpty();
    }

    // ── Rename section — full-page form ────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_section_edit_renders_form_with_existing_title() throws Exception {
        Section saved = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Tên cũ", (short) 0, lecturer.getId()));

        mockMvc.perform(get("/lecturer/classes/" + clazz.getId()
                        + "/lessons/sections/" + saved.getId() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/section-form"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(content().string(containsString("Tên cũ")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_section_edit_with_valid_title_updates_and_stays_on_edit_page() throws Exception {
        Section saved = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Tên cũ", (short) 0, lecturer.getId()));

        mockMvc.perform(post("/lecturer/classes/" + clazz.getId()
                        + "/lessons/sections/" + saved.getId() + "/edit")
                        .with(csrf())
                        .param("title", "Tên mới"))
                .andExpect(status().is3xxRedirection())
                // Stay on the edit page so the lecturer can see the
                // RENAMED activity row appear in the history tab.
                .andExpect(redirectedUrl(
                        "/lecturer/classes/" + clazz.getId()
                                + "/lessons/sections/" + saved.getId() + "/edit"))
                .andExpect(flash().attributeExists("flashSuccess"));

        Section reloaded = sectionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Tên mới");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_section_edit_eagerly_renders_history_panel() throws Exception {
        // Create + rename through the service so two audit rows are
        // written (CREATED + RENAMED). The plain GET (no ?tab= param)
        // must already include the rendered history rows because both
        // panels are emitted in the initial response — tab switching is
        // purely client-side.
        var row = sectionsService.create(clazz.getId(), "Cũ",
                lecturer.getId(), Role.LECTURER);
        sectionsService.rename(clazz.getId(), row.id(), "Mới",
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get("/lecturer/classes/" + clazz.getId()
                        + "/lessons/sections/" + row.id() + "/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/section-form"))
                .andExpect(model().attributeExists("activityPage"))
                .andExpect(content().string(containsString("Đổi tên")))
                .andExpect(content().string(containsString("Tạo mới")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_section_edit_with_history_tab_param_marks_active_tab() throws Exception {
        Section saved = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "T", (short) 0, lecturer.getId()));

        mockMvc.perform(get("/lecturer/classes/" + clazz.getId()
                        + "/lessons/sections/" + saved.getId() + "/edit")
                        .param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "history"));
    }

    // ── Delete section — JSON ──────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_section_marks_soft_deleted_in_db() throws Exception {
        Section saved = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "To-be-deleted", (short) 0, lecturer.getId()));

        mockMvc.perform(delete("/lecturer/classes/" + clazz.getId()
                        + "/lessons/sections/" + saved.getId())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // Soft-deleted rows are filtered out by @SQLRestriction in the default repository finders.
        assertThat(sectionRepository.findByClassIdOrderByDisplayOrderAsc(clazz.getId()))
                .isEmpty();
    }

    // ── Reorder — JSON ─────────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_reorder_with_full_ordered_ids_returns_200() throws Exception {
        Section a = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "A", (short) 0, lecturer.getId()));
        Section b = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "B", (short) 1, lecturer.getId()));
        Section c = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "C", (short) 2, lecturer.getId()));

        ReorderRequest body = new ReorderRequest(Arrays.asList(c.getId(), a.getId(), b.getId()));

        mockMvc.perform(post("/lecturer/classes/" + clazz.getId() + "/lessons/sections/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        List<Section> reloaded = sectionRepository.findByClassIdOrderByDisplayOrderAsc(clazz.getId());
        assertThat(reloaded).extracting(Section::getId)
                .containsExactly(c.getId(), a.getId(), b.getId());
    }

    // ── Cross-owner forbidden ──────────────────────────────────────────

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_can_create_section_in_lecturer_class() throws Exception {
        // HEAD has org-wide editing authority, so this should succeed
        // (proves the auth wiring uses isEditableBy, not lecturer-only).
        mockMvc.perform(post("/lecturer/classes/" + clazz.getId() + "/lessons/sections")
                        .with(csrf())
                        .param("title", "Section by HEAD"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));
        assertThat(head.getRole()).isEqualTo(Role.HEAD);
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_cannot_access_lessons_page() throws Exception {
        // Class-level @PreAuthorize blocks STUDENT: Spring Security returns
        // 403 for an authenticated user lacking the required role.
        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/classes/" + clazz.getId() + "/lessons"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
