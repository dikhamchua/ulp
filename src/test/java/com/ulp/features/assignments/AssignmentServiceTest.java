package com.ulp.features.assignments;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentForm;
import com.ulp.features.assignments.dto.AssignmentDtos.AssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.GradeForm;
import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentDetail;
import com.ulp.features.assignments.dto.AssignmentDtos.StudentAssignmentRow;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmissionRow;
import com.ulp.features.assignments.dto.AssignmentDtos.SubmitForm;
import com.ulp.features.assignments.entity.AssignmentStatus;
import com.ulp.features.assignments.service.AssignmentService;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link AssignmentService}.
 *
 * <p>Every test creates a fresh class + enrolled student via real repositories
 * so state never bleeds across tests (all wrapped in @Transactional).
 */
@SpringBootTest
@Transactional
class AssignmentServiceTest {

    @Autowired private AssignmentService assignmentService;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User student;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student  = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        clazz    = saveClass("Assignment IT class", lecturer.getId(), "ASGNIT" + System.nanoTime() % 10000);
        // Enroll the student so assignment operations succeed.
        enrollmentRepository.saveAndFlush(
                Enrollment.createFor(student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Test
    void create_returns_row_with_draft_status() {
        AssignmentForm form = new AssignmentForm(
                null, "Bài tập 1", "Mô tả",
                BigDecimal.valueOf(100), null, false);

        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);

        List<AssignmentRow> rows = assignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(AssignmentStatus.DRAFT);
        assertThat(rows.get(0).title()).isEqualTo("Bài tập 1");
    }

    @Test
    void create_fails_for_non_owner_lecturer() {
        User other = ensureUser("other-lect@ulp.edu.vn", "Other Lect", Role.LECTURER);
        AssignmentForm form = new AssignmentForm(null, "X", "Y", BigDecimal.TEN, null, false);

        assertThatThrownBy(() ->
                assignmentService.create(clazz.getId(), form, other.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Publish lifecycle ─────────────────────────────────────────────────

    @Test
    void publish_transitions_draft_to_published() {
        AssignmentForm form = new AssignmentForm(
                null, "Bài tập pub", "Mô tả",
                BigDecimal.valueOf(100), null, false);
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = assignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();

        assignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        AssignmentRow row = assignmentService
                .listForLecturer(clazz.getId(), lecturer.getId(), Role.LECTURER).get(0);
        assertThat(row.status()).isEqualTo(AssignmentStatus.PUBLISHED);
    }

    @Test
    void close_transitions_published_to_closed() {
        Long aid = createAndPublish("Bài tập close");

        assignmentService.close(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        AssignmentRow row = assignmentService
                .listForLecturer(clazz.getId(), lecturer.getId(), Role.LECTURER).get(0);
        assertThat(row.status()).isEqualTo(AssignmentStatus.CLOSED);
    }

    @Test
    void close_on_draft_throws_illegal_state() {
        AssignmentForm form = new AssignmentForm(null, "X", "Y", BigDecimal.TEN, null, false);
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = assignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();

        assertThatThrownBy(() ->
                assignmentService.close(clazz.getId(), aid, lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Student submit ────────────────────────────────────────────────────

    @Test
    void submit_creates_submitted_status() {
        Long aid = createAndPublish("Bài tập submit");

        assignmentService.submit(clazz.getId(), aid, new SubmitForm("Nội dung bài làm"), student.getId());

        List<StudentAssignmentRow> rows = assignmentService
                .listPublishedForStudent(clazz.getId(), student.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).submissionStatus()).isEqualTo(AssignmentStatus.SUB_SUBMITTED);
    }

    @Test
    void submit_after_graded_throws_illegal_state() {
        Long aid = createAndPublish("Bài tập grade guard");
        assignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());

        List<SubmissionRow> subs = assignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);
        Long sid = subs.get(0).submissionId();
        assignmentService.grade(clazz.getId(), aid, sid,
                new GradeForm(BigDecimal.valueOf(90), "Tốt"),
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() ->
                assignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp lại"), student.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_late_when_due_date_passed_and_late_allowed() {
        AssignmentForm form = new AssignmentForm(
                null, "Late allowed",  "Mô tả",
                BigDecimal.valueOf(100),
                LocalDateTime.now().minusDays(1), // due date in the past
                true);
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = assignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();
        assignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        assignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp muộn"), student.getId());

        StudentAssignmentDetail detail =
                assignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.isLate()).isTrue();
    }

    @Test
    void submit_late_when_due_passed_and_late_not_allowed_throws() {
        AssignmentForm form = new AssignmentForm(
                null, "No late", "Mô tả",
                BigDecimal.valueOf(100),
                LocalDateTime.now().minusDays(1),
                false); // late NOT allowed
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = assignmentService.listForLecturer(
                clazz.getId(), lecturer.getId(), Role.LECTURER).get(0).id();
        assignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() ->
                assignmentService.submit(clazz.getId(), aid, new SubmitForm("Nộp muộn"), student.getId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Grade ─────────────────────────────────────────────────────────────

    @Test
    void grade_sets_submission_to_graded_with_score() {
        Long aid = createAndPublish("Bài tập grading");
        assignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());
        Long sid = assignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER)
                .get(0).submissionId();

        assignmentService.grade(clazz.getId(), aid, sid,
                new GradeForm(BigDecimal.valueOf(85), "Khá tốt"),
                lecturer.getId(), Role.LECTURER);

        StudentAssignmentDetail detail =
                assignmentService.getForStudent(clazz.getId(), aid, student.getId());
        assertThat(detail.submissionStatus()).isEqualTo(AssignmentStatus.SUB_GRADED);
        assertThat(detail.score()).isEqualByComparingTo(BigDecimal.valueOf(85));
        assertThat(detail.feedback()).isEqualTo("Khá tốt");
    }

    @Test
    void grade_score_above_max_throws_illegal_argument() {
        Long aid = createAndPublish("Score cap test");
        assignmentService.submit(clazz.getId(), aid, new SubmitForm("Bài làm"), student.getId());
        Long sid = assignmentService
                .listSubmissions(clazz.getId(), aid, lecturer.getId(), Role.LECTURER)
                .get(0).submissionId();

        // max_score defaults to 100; submitting 101 must fail.
        assertThatThrownBy(() ->
                assignmentService.grade(clazz.getId(), aid, sid,
                        new GradeForm(BigDecimal.valueOf(101), null),
                        lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Student visibility ────────────────────────────────────────────────

    @Test
    void draft_assignment_not_visible_to_student() {
        AssignmentForm form = new AssignmentForm(null, "Draft only", "X", BigDecimal.TEN, null, false);
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);

        List<StudentAssignmentRow> rows =
                assignmentService.listPublishedForStudent(clazz.getId(), student.getId());
        assertThat(rows).isEmpty();
    }

    @Test
    void unenrolled_student_cannot_see_assignment() {
        User stranger = ensureUser("stranger@ulp.edu.vn", "Stranger", Role.STUDENT);
        Long aid = createAndPublish("Enrolled-only");

        assertThatThrownBy(() ->
                assignmentService.getForStudent(clazz.getId(), aid, stranger.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Long createAndPublish(String title) {
        AssignmentForm form = new AssignmentForm(
                null, title, "Mô tả", BigDecimal.valueOf(100), null, false);
        assignmentService.create(clazz.getId(), form, lecturer.getId(), Role.LECTURER);
        Long aid = assignmentService
                .listForLecturer(clazz.getId(), lecturer.getId(), Role.LECTURER)
                .stream().filter(r -> title.equals(r.title()))
                .findFirst().orElseThrow().id();
        assignmentService.publish(clazz.getId(), aid, lecturer.getId(), Role.LECTURER);
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
