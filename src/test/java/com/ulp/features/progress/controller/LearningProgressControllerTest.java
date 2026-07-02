package com.ulp.features.progress.controller;

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
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link LearningProgressController} (ULP-4.5): PRG redirect
 * with flash, 404 for outsiders, and CSRF enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LearningProgressControllerTest {

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
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = userRepository.findByEmailIgnoreCase(STUDENT_EMAIL).orElseThrow();
        clazz = saveClass("Progress controller class", "PRGCTL");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Lesson l = new Lesson(section.getId(), "Bài 1", (short) 0, lecturer.getId());
        l.updateContent("<p>Body</p>");
        l.publish();
        lesson = lessonRepository.saveAndFlush(l);
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void toggle_redirects_with_flash() throws Exception {
        String expected = "/my/classes/" + clazz.getId() + "/lessons"
                + "?section=" + section.getId() + "&lesson=" + lesson.getId();

        mockMvc.perform(post(toggleUrl()).param("section", section.getId().toString()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expected))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithUserDetails(OUTSIDER_EMAIL)
    void outsider_toggle_returns_404() throws Exception {
        mockMvc.perform(post(toggleUrl()).param("section", section.getId().toString()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void toggle_without_csrf_is_forbidden() throws Exception {
        mockMvc.perform(post(toggleUrl()).param("section", section.getId().toString()))
                .andExpect(status().isForbidden());
    }

    private String toggleUrl() {
        return "/my/classes/" + clazz.getId() + "/lessons/" + lesson.getId() + "/progress/toggle";
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
