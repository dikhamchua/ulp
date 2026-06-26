package com.ulp.features.lessons.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonReorderRequest;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.security.Role;
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
 * Integration tests for {@link LessonsController}. Drives every Lesson
 * CRUD endpoint through {@link MockMvc} so the security filter chain,
 * content negotiation, validation, and cross-class authorization checks
 * are all exercised end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LessonsControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonsService lessonsService;

    private User lecturer;
    private ClassEntity clazz;
    private Section section;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Lessons CIT class", lecturer.getId(), "LSNCIT");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
    }

    // ── Render create form ────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_new_form_renders_for_owner() throws Exception {
        mockMvc.perform(get(newUrl()))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/lesson-form"))
                .andExpect(model().attribute("mode", "create"))
                .andExpect(model().attributeExists("form"))
                .andExpect(model().attributeExists("section"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_create_with_valid_input_redirects_with_flash() throws Exception {
        mockMvc.perform(post(baseUrl())
                        .with(csrf())
                        .param("title", "Bài 1")
                        .param("status", "DRAFT")
                        .param("contentHtml", "<p>Nội dung</p>"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(lessonsTabUrl()))
                .andExpect(flash().attributeExists("flashSuccess"));

        List<Lesson> stored = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(section.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTitle()).isEqualTo("Bài 1");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_create_with_blank_title_re_renders_form_with_error() throws Exception {
        mockMvc.perform(post(baseUrl())
                        .with(csrf())
                        .param("title", "   ")
                        .param("status", "DRAFT")
                        .param("contentHtml", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/lesson-form"))
                .andExpect(model().attributeHasFieldErrors("form", "title"));

        assertThat(lessonRepository.findBySectionIdOrderByDisplayOrderAsc(section.getId()))
                .isEmpty();
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_create_sanitises_script_in_body() throws Exception {
        mockMvc.perform(post(baseUrl())
                        .with(csrf())
                        .param("title", "Bài 1")
                        .param("status", "DRAFT")
                        .param("contentHtml", "<p>OK</p><script>x()</script>"))
                .andExpect(status().is3xxRedirection());

        Lesson stored = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(section.getId()).get(0);
        assertThat(stored.getContentRichtext()).contains("<p>OK</p>");
        assertThat(stored.getContentRichtext()).doesNotContain("<script>");
    }

    // ── Edit form ──────────────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_edit_form_pre_fills_form_and_eager_loads_activity_page() throws Exception {
        var row = lessonsService.create(clazz.getId(), section.getId(),
                "Cũ", "DRAFT", "<p>body</p>",
                lecturer.getId(), Role.LECTURER);
        lessonsService.update(clazz.getId(), section.getId(), row.id(),
                "Mới", "DRAFT", "<p>body</p>",
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(get(editUrl(row.id())))
                .andExpect(status().isOk())
                .andExpect(view().name("classes/lesson-form"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(model().attributeExists("activityPage"))
                .andExpect(content().string(containsString("Mới")))
                .andExpect(content().string(containsString("Cập nhật")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void get_edit_tab_history_marks_active_tab() throws Exception {
        Lesson saved = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "T", (short) 0, lecturer.getId()));

        mockMvc.perform(get(editUrl(saved.getId())).param("tab", "history"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("activeDetailTab", "history"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_edit_with_valid_input_redirects_to_edit_page() throws Exception {
        Lesson saved = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "Cũ", (short) 0, lecturer.getId()));

        mockMvc.perform(post(editUrl(saved.getId()))
                        .with(csrf())
                        .param("title", "Mới")
                        .param("status", "DRAFT")
                        .param("contentHtml", "<p>updated</p>"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(editUrl(saved.getId())))
                .andExpect(flash().attributeExists("flashSuccess"));

        Lesson reloaded = lessonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Mới");
    }

    // ── Publish / unpublish ────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_publish_changes_status() throws Exception {
        Lesson saved = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "T", (short) 0, lecturer.getId()));

        mockMvc.perform(post(baseUrl() + "/" + saved.getId() + "/publish")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashSuccess"));

        Lesson reloaded = lessonRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_unpublish_changes_status() throws Exception {
        // Publish first so unpublish has something to flip.
        var row = lessonsService.create(clazz.getId(), section.getId(),
                "T", "PUBLISHED", "",
                lecturer.getId(), Role.LECTURER);

        mockMvc.perform(post(baseUrl() + "/" + row.id() + "/unpublish")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DRAFT");
    }

    // ── Delete + reorder JSON ─────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void delete_returns_json_and_soft_deletes_lesson() throws Exception {
        Lesson saved = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "X", (short) 0, lecturer.getId()));

        mockMvc.perform(delete(baseUrl() + "/" + saved.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(lessonRepository.findBySectionIdOrderByDisplayOrderAsc(section.getId()))
                .isEmpty();
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void post_reorder_with_full_ordered_ids_returns_ok() throws Exception {
        Lesson a = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "A", (short) 0, lecturer.getId()));
        Lesson b = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "B", (short) 1, lecturer.getId()));
        Lesson c = lessonRepository.saveAndFlush(
                new Lesson(section.getId(), "C", (short) 2, lecturer.getId()));

        LessonReorderRequest body = new LessonReorderRequest(
                Arrays.asList(c.getId(), a.getId(), b.getId()));

        mockMvc.perform(post(baseUrl() + "/reorder")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        List<Lesson> reloaded = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(section.getId());
        assertThat(reloaded).extracting(Lesson::getId)
                .containsExactly(c.getId(), a.getId(), b.getId());
    }

    // ── Negative auth ──────────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_cannot_access_lesson_endpoints() throws Exception {
        mockMvc.perform(get(newUrl()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get(newUrl()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    /**
     * Cross-class enumeration attack: lecturer B owns a separate class,
     * tries to POST against lecturer A's section URL. Service must reject
     * with 403 even though B is a valid lecturer. Verifies that the
     * authorization runs on (classId, sectionId) and not just on role.
     */
    @Test
    void lecturer_from_another_class_is_rejected() throws Exception {
        User otherLecturer = ensureExtraLecturer();
        // The other lecturer's own class exists but is irrelevant — we
        // attack the seeded `clazz` owned by the seeded lecturer.
        saveClass("Other lecturer class", otherLecturer.getId(), "OTHER1");

        mockMvc.perform(post(baseUrl())
                        .with(csrf())
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user(
                                        new com.ulp.security.UlpUserDetails(otherLecturer)))
                        .param("title", "Bài lậu")
                        .param("status", "DRAFT")
                        .param("contentHtml", ""))
                .andExpect(status().isForbidden());

        assertThat(lessonRepository.findBySectionIdOrderByDisplayOrderAsc(section.getId()))
                .isEmpty();
    }

    // ── URL helpers ───────────────────────────────────────────────────

    private String baseUrl() {
        return "/lecturer/classes/" + clazz.getId()
                + "/sections/" + section.getId() + "/lessons";
    }

    private String newUrl() {
        return baseUrl() + "/new";
    }

    private String editUrl(Long lessonId) {
        return baseUrl() + "/" + lessonId + "/edit";
    }

    private String lessonsTabUrl() {
        return "/lecturer/classes/" + clazz.getId() + "/lessons?section=" + section.getId();
    }

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

    private User ensureExtraLecturer() {
        String email = "lecturer-other@ulp.edu.vn";
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    "Lecturer Other",
                    Role.LECTURER,
                    true,
                    null,
                    null);
            return userRepository.saveAndFlush(u);
        });
    }
}
