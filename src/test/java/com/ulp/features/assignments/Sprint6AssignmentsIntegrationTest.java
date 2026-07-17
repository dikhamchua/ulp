package com.ulp.features.assignments;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ulp.features.assignments.service.LecturerAssignmentService;
import com.ulp.features.assignments.service.StudentAssignmentService;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 6 integration tests for the assignments feature.
 *
 * <p>Drives lecturer and student flows end-to-end via MockMvc so the full
 * Spring Security filter chain, controller layer, and service layer participate.
 * Uses {@code @WithUserDetails} (not {@code @WithMockUser}) because the
 * controllers rely on {@code @AuthenticationPrincipal UlpUserDetails}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint6AssignmentsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LecturerAssignmentService lecturerAssignmentService;
    @Autowired private StudentAssignmentService studentAssignmentService;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Long classId;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student  = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        clazz    = saveClass("Sprint6 IT class", lecturer.getId(), "S6IT" + System.nanoTime() % 10000);
        classId  = clazz.getId();
        enrollmentRepository.saveAndFlush(
                Enrollment.createFor(student, classId, Enrollment.JoinedVia.CODE, null));
    }

    // ── Lecturer list page ────────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_can_view_assignment_list() throws Exception {
        mockMvc.perform(get("/lecturer/classes/{id}/assignments", classId))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_cannot_access_lecturer_list() throws Exception {
        // LECTURER+ route — student should be forbidden.
        mockMvc.perform(get("/lecturer/classes/{id}/assignments", classId))
                .andExpect(status().isForbidden());
    }

    // ── Lecturer create form ──────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_new_form_renders_ok() throws Exception {
        mockMvc.perform(get("/lecturer/classes/{id}/assignments/new", classId))
                .andExpect(status().isOk());
    }

    // ── Lecturer create POST (PRG) ────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_create_post_redirects_to_list() throws Exception {
        mockMvc.perform(post("/lecturer/classes/{id}/assignments", classId)
                        .with(csrf())
                        .param("title", "Bài tập Sprint 6")
                        .param("description", "Mô tả bài tập")
                        .param("maxScore", "100")
                        .param("allowLateSubmission", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/lecturer/classes/*/assignments"));
    }

    // ── CSRF protection ───────────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_create_without_csrf_returns_403() throws Exception {
        mockMvc.perform(post("/lecturer/classes/{id}/assignments", classId)
                        // No .with(csrf()) — should be rejected.
                        .param("title", "No CSRF")
                        .param("description", "Should fail"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_publish_without_csrf_returns_403() throws Exception {
        Long aid = createDraftAssignment();

        mockMvc.perform(post("/lecturer/classes/{id}/assignments/{aid}/publish",
                        classId, aid))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_submit_without_csrf_returns_403() throws Exception {
        Long aid = createAndPublishAssignment();

        mockMvc.perform(post("/classes/{id}/assignments/{aid}/submit", classId, aid)
                        .param("content", "Bài làm"))
                .andExpect(status().isForbidden());
    }

    // ── Lecturer publish lifecycle ────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_publish_redirects_to_list() throws Exception {
        Long aid = createDraftAssignment();

        mockMvc.perform(post("/lecturer/classes/{id}/assignments/{aid}/publish",
                        classId, aid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/lecturer/classes/*/assignments"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_close_redirects_to_list() throws Exception {
        Long aid = createAndPublishAssignment();

        mockMvc.perform(post("/lecturer/classes/{id}/assignments/{aid}/close",
                        classId, aid).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/lecturer/classes/*/assignments"));
    }

    // ── Lecturer submissions page ─────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_submissions_page_renders_ok() throws Exception {
        Long aid = createAndPublishAssignment();

        mockMvc.perform(get("/lecturer/classes/{id}/assignments/{aid}/submissions",
                        classId, aid))
                .andExpect(status().isOk());
    }

    // ── Student list page ─────────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_can_view_assignment_list() throws Exception {
        mockMvc.perform(get("/classes/{id}/assignments", classId))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_user_redirected_to_login_on_student_list() throws Exception {
        mockMvc.perform(get("/classes/{id}/assignments", classId))
                .andExpect(status().is3xxRedirection());
    }

    // ── Student detail page ───────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_can_view_published_assignment_detail() throws Exception {
        Long aid = createAndPublishAssignment();

        mockMvc.perform(get("/classes/{id}/assignments/{aid}", classId, aid))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_gets_404_for_draft_assignment() throws Exception {
        Long aid = createDraftAssignment();

        // DRAFT assignments are not visible to students.
        mockMvc.perform(get("/classes/{id}/assignments/{aid}", classId, aid))
                .andExpect(status().isNotFound());
    }

    // ── Student submit ────────────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_submit_redirects_to_detail() throws Exception {
        Long aid = createAndPublishAssignment();

        mockMvc.perform(post("/classes/{id}/assignments/{aid}/submit", classId, aid)
                        .with(csrf())
                        .param("content", "Nội dung bài làm của tôi"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/classes/*/assignments/*"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_submit_late_when_not_allowed_returns_redirect_not_5xx() throws Exception {
        // Create and publish an assignment with due date in the past and late NOT allowed.
        AssignmentForm form = new AssignmentForm(
                null, "Past due no late", "Desc", BigDecimal.valueOf(100),
                LocalDateTime.now().minusDays(1), false);
        lecturerAssignmentService.create(classId, form, lecturer.getId(), Role.LECTURER);
        Long aid = lecturerAssignmentService
                .listForLecturer(classId, lecturer.getId(), Role.LECTURER)
                .stream().filter(r -> "Past due no late".equals(r.title()))
                .findFirst().orElseThrow().id();
        lecturerAssignmentService.publish(classId, aid, lecturer.getId(), Role.LECTURER);

        // Controller must redirect (3xx) with a flash error, not a 500.
        mockMvc.perform(post("/classes/{id}/assignments/{aid}/submit", classId, aid)
                        .with(csrf())
                        .param("content", "Late attempt"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_resubmit_after_graded_returns_redirect_not_5xx() throws Exception {
        Long aid = createAndPublishAssignment();
        // First submission.
        studentAssignmentService.submit(classId, aid, new SubmitForm("Bài làm"), student.getId());
        // Grade the submission.
        Long sid = lecturerAssignmentService
                .listSubmissions(classId, aid, lecturer.getId(), Role.LECTURER)
                .get(0).submissionId();
        lecturerAssignmentService.grade(classId, aid, sid,
                new GradeForm(BigDecimal.valueOf(80), "OK"),
                lecturer.getId(), Role.LECTURER);

        // Re-submit after GRADED — controller must redirect with flash error, not 500.
        mockMvc.perform(post("/classes/{id}/assignments/{aid}/submit", classId, aid)
                        .with(csrf())
                        .param("content", "Nộp lại sau khi chấm"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("flashError"));
    }

    // ── Non-owner isolation ───────────────────────────────────────────────

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_gets_404_for_class_they_do_not_own() throws Exception {
        // Create a class owned by a different lecturer.
        User other = ensureUser("other2@ulp.edu.vn", "Other2", Role.LECTURER);
        ClassEntity otherClass = saveClass("Other class", other.getId(), "OTHS6");

        mockMvc.perform(get("/lecturer/classes/{id}/assignments", otherClass.getId()))
                .andExpect(status().isNotFound());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long createDraftAssignment() {
        AssignmentForm form = new AssignmentForm(
                null, "Test assignment", "Mô tả", BigDecimal.valueOf(100), null, false);
        lecturerAssignmentService.create(classId, form, lecturer.getId(), Role.LECTURER);
        return lecturerAssignmentService
                .listForLecturer(classId, lecturer.getId(), Role.LECTURER)
                .get(0).id();
    }

    private Long createAndPublishAssignment() {
        Long aid = createDraftAssignment();
        lecturerAssignmentService.publish(classId, aid, lecturer.getId(), Role.LECTURER);
        return aid;
    }

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity c = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        c.setCode(code);
        return classRepository.saveAndFlush(c);
    }

    private User ensureUser(String email, String fullName, Role role) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    fullName,
                    role,
                    true,
                    null,
                    null);
            return userRepository.saveAndFlush(u);
        });
    }
}
