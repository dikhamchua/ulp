package com.ulp.features.classes.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.LearningProgress;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.security.Role;
import com.ulp.security.UlpUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration tests for {@link ClassProgressApiController} (drill-down JSON + status codes). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ClassProgressApiControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LearningProgressRepository progressRepository;

    private User lecturer;
    private ClassEntity clazz;
    private User member;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Drill class", lecturer.getId(), "DRILL1");
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Lesson a = publishLesson(section.getId(), "A", (short) 0);
        publishLesson(section.getId(), "B", (short) 1);
        member = ensureUser("drill-member@ulp.edu.vn", "Drill Member", Role.STUDENT);
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                member, clazz.getId(), Enrollment.JoinedVia.CODE, null));
        LearningProgress done = new LearningProgress(member.getId(), a.getId());
        done.markCompleted();
        progressRepository.saveAndFlush(done);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void owner_gets_json_breakdown() throws Exception {
        mockMvc.perform(get(drillUrl(member.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections[0].title").value("Chương 1"))
                .andExpect(jsonPath("$.sections[0].lessons[0].title").value("A"))
                .andExpect(jsonPath("$.sections[0].lessons[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.sections[0].lessons[1].status").value("NOT_STARTED"));
    }

    @Test
    void non_owner_lecturer_gets_403() throws Exception {
        User otherLecturer = ensureUser("lecturer-drillother@ulp.edu.vn", "Other", Role.LECTURER);
        mockMvc.perform(get(drillUrl(member.getId()))
                        .with(user(new UlpUserDetails(otherLecturer))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void non_member_student_gets_404() throws Exception {
        User outsider = ensureUser("drill-outsider@ulp.edu.vn", "Outsider", Role.STUDENT);
        mockMvc.perform(get(drillUrl(outsider.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_is_denied_by_security() throws Exception {
        mockMvc.perform(get(drillUrl(member.getId())))
                .andExpect(status().isForbidden());
    }

    private String drillUrl(Long studentId) {
        return "/lecturer/classes/" + clazz.getId() + "/progress/" + studentId + "/lessons";
    }

    private Lesson publishLesson(Long sectionId, String title, short order) {
        Lesson l = new Lesson(sectionId, title, order, lecturer.getId());
        l.publish();
        return lessonRepository.saveAndFlush(l);
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

    private User ensureUser(String email, String name, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, role, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
