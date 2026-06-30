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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the legacy standalone lesson detail endpoint
 * {@code GET /my/classes/{classId}/lessons/{lessonId}} (ULP-4.2).
 *
 * <p>After the single-template refactor the route returns a permanent
 * 301 redirect to the canonical query-param form
 * ({@code /my/classes/{classId}/lessons?section=X&lesson=Y}); rendering
 * happens inside the 3-column list view (see
 * {@link StudentLessonsControllerTest} for the rendered-content
 * assertions). These tests cover the redirect contract and the unchanged
 * 404 gates (not enrolled / DRAFT lesson / anonymous).
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

    /**
     * Core contract: enrolled student hitting the legacy URL gets a
     * permanent (301) redirect to the canonical query-param form.
     */
    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void redirect_standalone_lesson_url_to_query_param_form() throws Exception {
        String expectedLocation = "/my/classes/" + clazz.getId() + "/lessons"
                + "?section=" + section.getId()
                + "&lesson=" + published.getId();

        mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string(HttpHeaders.LOCATION, expectedLocation));
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void get_as_not_enrolled_returns_404_with_message() throws Exception {
        mockMvc.perform(get(url(clazz.getId(), published.getId())))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Class not found or not accessible")));
    }

    /** DRAFT lessons must 404 — never leak existence via redirect. */
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
