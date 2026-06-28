package com.ulp.features.student.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for the student-facing lesson detail endpoint
 * {@code GET /my/classes/{classId}/lessons/{lessonId}} (ULP-4.2).
 *
 * <p>Exercises the Spring Security filter chain, the controller wiring,
 * template selection, and the {@code EntityNotFoundException} → 404
 * mapping end-to-end via MockMvc. Pre-seeded users from migrations are
 * used because {@code @WithUserDetails} resolves the principal before
 * {@code @BeforeEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentLessonDetailControllerTest {

    private static final String STUDENT_EMAIL = "student@ulp.edu.vn";
    private static final String OUTSIDER_EMAIL = "sv01@ulp.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;
    private Lesson published;
    private Lesson draft;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase(STUDENT_EMAIL).orElseThrow();
        clazz = saveClass("Detail controller class", lecturer.getId(), "SLDCTC");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));

        Lesson pub = new Lesson(section.getId(), "Bài 1", (short) 0, lecturer.getId());
        pub.updateContent("<p>Body</p>");
        pub.publish();
        published = lessonRepository.saveAndFlush(pub);

        Lesson dr = new Lesson(section.getId(), "Bài nháp", (short) 1, lecturer.getId());
        draft = lessonRepository.saveAndFlush(dr);

        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_as_enrolled_returns_200_with_view_and_model() throws Exception {
        var result = mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("student/lesson-detail"))
                .andExpect(model().attributeExists("lessonDetail"))
                // Back link must carry the section id so the list view re-opens the same section.
                .andExpect(content().string(containsString("?section=" + section.getId())))
                // Lesson title is rendered inside an <h1> wrapper.
                .andExpect(content().string(containsString("<h1")))
                .andExpect(content().string(containsString("Bài 1")))
                // Rich-text body must be unescaped (th:utext), proven by the raw <p> tag.
                .andExpect(content().string(containsString("<p>Body</p>")))
                .andReturn();

        LessonDetailView detail = (LessonDetailView) result.getModelAndView()
                .getModel().get("lessonDetail");
        assertThat(detail).isNotNull();
        assertThat(detail.lessonId()).isEqualTo(published.getId());
        assertThat(detail.classId()).isEqualTo(clazz.getId());
        assertThat(detail.lessonTitle()).isEqualTo("Bài 1");
    }

    /** Empty rich-text content renders the Vietnamese placeholder paragraph. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_as_enrolled_with_empty_content_renders_placeholder() throws Exception {
        // Arrange a published lesson whose content_richtext is null.
        Lesson empty = new Lesson(section.getId(), "Bài trống", (short) 2, lecturer.getId());
        empty.publish();
        empty = lessonRepository.saveAndFlush(empty);

        mockMvc.perform(get(url(clazz.getId(), empty.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bài giảng này chưa có nội dung")));
    }

    /** When the lesson has zero attachments the entire section block is omitted from the HTML. */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_as_enrolled_with_no_attachments_hides_attachments_section() throws Exception {
        // The fixture's `published` lesson has no attachments by default.
        mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("student-lesson-detail-attachments"))))
                .andExpect(content().string(not(containsString("Tệp đính kèm"))));
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void get_as_not_enrolled_returns_404_with_message() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Class not found or not accessible")));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void get_draft_lesson_returns_404() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), draft.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithAnonymousUser
    void get_as_anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String url(Long classId, Long lessonId) {
        return "/my/classes/" + classId + "/lessons/" + lessonId;
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
}
