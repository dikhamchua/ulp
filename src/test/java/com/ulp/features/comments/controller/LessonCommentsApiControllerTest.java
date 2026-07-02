package com.ulp.features.comments.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import com.ulp.features.comments.service.LessonCommentsService;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link LessonCommentsApiController} (ULP-4.6): status codes,
 * JSON envelope shape, and the authz matrix (404 for outsider / DRAFT, 400 for
 * bad input, 403 for cross-user delete).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LessonCommentsApiControllerTest {

    private static final String AUTHOR_EMAIL = "student@ulp.edu.vn";  // enrolled
    private static final String OTHER_EMAIL = "sv02@ulp.edu.vn";      // enrolled
    private static final String OUTSIDER_EMAIL = "sv01@ulp.edu.vn";   // not enrolled

    @Autowired private MockMvc mockMvc;
    @Autowired private LessonCommentsService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User author;
    private User other;
    private ClassEntity clazz;
    private Section section;
    private Lesson lesson;
    private Lesson draft;
    private short orderSeq;

    @BeforeEach
    void setUp() {
        orderSeq = 0;
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        author = userRepository.findByEmailIgnoreCase(AUTHOR_EMAIL).orElseThrow();
        other = userRepository.findByEmailIgnoreCase(OTHER_EMAIL).orElseThrow();
        clazz = saveClass("Comments API class", "CMTAPI");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        lesson = persistLesson("Bài 1", true);
        draft = persistLesson("Bài nháp", false);
        enroll(author);
        enroll(other);
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void list_returns_ok_envelope() throws Exception {
        mockMvc.perform(get(url()).param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.comments").isArray())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.hasNext").exists())
                .andExpect(jsonPath("$.data.totalRoots").exists());
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void list_paginates_roots_newest_first() throws Exception {
        for (int i = 0; i < 12; i++) {
            service.create(lesson.getId(), author.getId(), "Gốc " + i, null);
        }

        mockMvc.perform(get(url()).param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments.length()").value(5))
                .andExpect(jsonPath("$.data.totalRoots").value(12))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                // Newest-first: last-created root leads the first page.
                .andExpect(jsonPath("$.data.comments[0].content").value("Gốc 11"));
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void outsider_list_returns_404() throws Exception {
        mockMvc.perform(get(url()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void draft_lesson_list_returns_404() throws Exception {
        mockMvc.perform(get("/api/lessons/" + draft.getId() + "/comments"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void create_returns_ok_with_row() throws Exception {
        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Câu hỏi của tôi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.canDelete").value(true));
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void create_reply_echoes_parent_and_avatar() throws Exception {
        CommentRow root = service.create(lesson.getId(), author.getId(), "Gốc", null);

        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Trả lời\",\"parentId\":" + root.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(root.id()))
                .andExpect(jsonPath("$.data.avatarGradient").exists());
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void blank_content_returns_400() throws Exception {
        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void cross_lesson_parent_returns_400() throws Exception {
        Lesson lesson2 = persistLesson("Bài 2", true);
        CommentRow rootInOther = service.create(lesson2.getId(), author.getId(), "Gốc khác", null);

        mockMvc.perform(post(url()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Trả lời\",\"parentId\":" + rootInOther.id() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithUserDetails(OTHER_EMAIL)
    void other_student_delete_returns_403() throws Exception {
        CommentRow root = service.create(lesson.getId(), author.getId(), "Của tác giả", null);

        mockMvc.perform(delete(url() + "/" + root.id()).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(AUTHOR_EMAIL)
    void author_delete_returns_ok() throws Exception {
        CommentRow root = service.create(lesson.getId(), author.getId(), "Xoá tôi", null);

        mockMvc.perform(delete(url() + "/" + root.id()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    private String url() {
        return "/api/lessons/" + lesson.getId() + "/comments";
    }

    private Lesson persistLesson(String title, boolean published) {
        Lesson l = new Lesson(section.getId(), title, orderSeq++, lecturer.getId());
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private void enroll(User u) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
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
