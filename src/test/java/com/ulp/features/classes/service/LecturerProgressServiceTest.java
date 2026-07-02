package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.LearningProgress;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.dto.ProgressDtos.ProgressPageView;
import com.ulp.features.classes.dto.ProgressDtos.ProgressSummary;
import com.ulp.features.classes.dto.ProgressDtos.StudentBreakdown;
import com.ulp.features.classes.dto.ProgressDtos.StudentProgressRow;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for {@link LecturerProgressService}. */
@SpringBootTest
@Transactional
class LecturerProgressServiceTest {

    @Autowired private LecturerProgressService service;
    @Autowired private LecturerProgressBreakdownService breakdownService;
    @Autowired private com.ulp.features.classes.repository.ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private LearningProgressRepository progressRepository;
    @Autowired private UserRepository userRepository;

    private User lecturer;
    private ClassEntity clazz;
    private Section section1;
    private Section section2;
    private List<Lesson> published;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Progress class", "PROG01");
        section1 = sectionRepository.saveAndFlush(new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        section2 = sectionRepository.saveAndFlush(new Section(clazz.getId(), "Chương 2", (short) 1, lecturer.getId()));
    }

    @Test
    void summary_and_percent_over_full_cohort() {
        seedFourPublishedLessons();
        User s4 = enrollStudent("prog-s4@ulp.edu.vn", "Nguyen Bon");
        User s2 = enrollStudent("prog-s2@ulp.edu.vn", "Tran Hai");
        User s0 = enrollStudent("prog-s0@ulp.edu.vn", "Le Khong");
        completeLessons(s4, published); // 4/4 -> 100%
        completeLessons(s2, published.subList(0, 2)); // 2/4 -> 50%
        // s0 completes nothing.

        ProgressPageView view = service.getProgressPage(clazz.getId(), lecturer.getId(),
                Role.LECTURER, "all", "", 0, 10);

        ProgressSummary sum = view.summary();
        assertThat(sum.totalStudents()).isEqualTo(3);
        assertThat(sum.avgPercent()).isEqualTo(50); // round((100+50+0)/3)
        assertThat(sum.notStartedCount()).isEqualTo(1);
        assertThat(sum.completedCount()).isEqualTo(1);
    }

    @Test
    void draft_and_soft_deleted_lessons_excluded_from_denominator() {
        Lesson pub1 = persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        persistLesson(section1.getId(), "Nháp", (short) 1, false); // DRAFT
        Lesson deleted = persistLesson(section1.getId(), "Đã xoá", (short) 2, true);
        deleted.markDeleted();
        lessonRepository.saveAndFlush(deleted);
        User s = enrollStudent("prog-den@ulp.edu.vn", "Denominator");
        completeLessons(s, List.of(pub1));

        StudentProgressRow row = onlyRow(s.getId());
        assertThat(row.total()).isEqualTo(1); // only the published, non-deleted lesson
        assertThat(row.completed()).isEqualTo(1);
        assertThat(row.percent()).isEqualTo(100);
        assertThat(row.status()).isEqualTo("completed");
    }

    @Test
    void no_published_lessons_yields_zero_percent_without_error() {
        User s = enrollStudent("prog-empty@ulp.edu.vn", "Empty Class");

        ProgressPageView view = service.getProgressPage(clazz.getId(), lecturer.getId(),
                Role.LECTURER, "all", "", 0, 10);

        assertThat(view.summary().avgPercent()).isZero();
        assertThat(view.summary().completedCount()).isZero();
        StudentProgressRow row = view.rows().getContent().get(0);
        assertThat(row.total()).isZero();
        assertThat(row.percent()).isZero();
        assertThat(row.status()).isEqualTo("not-started");
    }

    @Test
    void search_matches_name_or_email_case_insensitive() {
        seedFourPublishedLessons();
        enrollStudent("alice@ulp.edu.vn", "Nguyen Van A");
        enrollStudent("bob@ulp.edu.vn", "Tran Thi B");

        Page<StudentProgressRow> byName = service.getProgressPage(clazz.getId(),
                lecturer.getId(), Role.LECTURER, "all", "NGUYEN", 0, 10).rows();
        assertThat(byName.getContent()).extracting(StudentProgressRow::fullName)
                .containsExactly("Nguyen Van A");

        Page<StudentProgressRow> byEmail = service.getProgressPage(clazz.getId(),
                lecturer.getId(), Role.LECTURER, "all", "bob@", 0, 10).rows();
        assertThat(byEmail.getContent()).extracting(StudentProgressRow::email)
                .containsExactly("bob@ulp.edu.vn");
    }

    @Test
    void status_filter_in_progress_keeps_only_partial_students() {
        seedFourPublishedLessons();
        User done = enrollStudent("f-done@ulp.edu.vn", "Full Done");
        User partial = enrollStudent("f-part@ulp.edu.vn", "Half Way");
        enrollStudent("f-none@ulp.edu.vn", "Not Yet");
        completeLessons(done, published);
        completeLessons(partial, published.subList(0, 1));

        Page<StudentProgressRow> inProgress = service.getProgressPage(clazz.getId(),
                lecturer.getId(), Role.LECTURER, "in-progress", "", 0, 10).rows();

        assertThat(inProgress.getContent()).extracting(StudentProgressRow::userId)
                .containsExactly(partial.getId());
        // Summary still spans the full cohort of 3, unaffected by the filter.
        assertThat(inProgress.getTotalElements()).isEqualTo(1);
    }

    @Test
    void summary_ignores_active_filter() {
        seedFourPublishedLessons();
        User done = enrollStudent("g-done@ulp.edu.vn", "GDone");
        enrollStudent("g-none@ulp.edu.vn", "GNone");
        completeLessons(done, published);

        ProgressSummary sum = service.getProgressPage(clazz.getId(), lecturer.getId(),
                Role.LECTURER, "completed", "", 0, 10).summary();

        assertThat(sum.totalStudents()).isEqualTo(2);
        assertThat(sum.completedCount()).isEqualTo(1);
        assertThat(sum.notStartedCount()).isEqualTo(1);
    }

    @Test
    void pagination_returns_window_and_total() {
        seedFourPublishedLessons();
        for (int i = 0; i < 25; i++) {
            enrollStudent("page-" + i + "@ulp.edu.vn", "Student " + i);
        }

        Page<StudentProgressRow> page1 = service.getProgressPage(clazz.getId(),
                lecturer.getId(), Role.LECTURER, "all", "", 1, 10).rows();

        assertThat(page1.getContent()).hasSize(10);
        assertThat(page1.getTotalElements()).isEqualTo(25);
        assertThat(page1.getTotalPages()).isEqualTo(3);
        assertThat(page1.getNumber()).isEqualTo(1);
    }

    @Test
    void last_activity_is_max_updated_at_across_any_status() {
        Lesson pub1 = persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        Lesson pub2 = persistLesson(section1.getId(), "Bài 2", (short) 1, true);
        User s = enrollStudent("act@ulp.edu.vn", "Activity Guy");
        // One IN_PROGRESS open (no completion) must still count as activity.
        progressRepository.saveAndFlush(new LearningProgress(s.getId(), pub1.getId()));
        LearningProgress done = new LearningProgress(s.getId(), pub2.getId());
        done.markCompleted();
        progressRepository.saveAndFlush(done);

        StudentProgressRow row = onlyRow(s.getId());
        assertThat(row.lastActivity()).isNotNull();
        assertThat(row.completed()).isEqualTo(1);
    }

    @Test
    void never_started_student_has_null_last_activity() {
        seedFourPublishedLessons();
        User s = enrollStudent("never@ulp.edu.vn", "Never Started");

        StudentProgressRow row = onlyRow(s.getId());
        assertThat(row.lastActivity()).isNull();
        assertThat(row.completed()).isZero();
        assertThat(row.status()).isEqualTo("not-started");
    }

    @Test
    void opened_but_zero_completed_buckets_as_in_progress() {
        seedFourPublishedLessons();
        User opener = enrollStudent("prog-opener@ulp.edu.vn", "Opener Only");
        // Open one lesson (IN_PROGRESS) without completing anything.
        progressRepository.saveAndFlush(
                new LearningProgress(opener.getId(), published.get(0).getId()));

        ProgressPageView view = service.getProgressPage(clazz.getId(), lecturer.getId(),
                Role.LECTURER, "all", "", 0, 10);

        StudentProgressRow row = view.rows().getContent().stream()
                .filter(r -> r.userId().equals(opener.getId())).findFirst().orElseThrow();
        assertThat(row.completed()).isZero();
        assertThat(row.lastActivity()).isNotNull();
        // Any activity means in-progress, never not-started (bucket-UX fix).
        assertThat(row.status()).isEqualTo("in-progress");
        // Activity excludes the student from the not-started summary count.
        assertThat(view.summary().notStartedCount()).isZero();
    }

    @Test
    void drill_down_maps_status_per_lesson_grouped_by_section() {
        Lesson a = persistLesson(section1.getId(), "A", (short) 0, true);
        Lesson b = persistLesson(section1.getId(), "B", (short) 1, true);
        Lesson c = persistLesson(section2.getId(), "C", (short) 0, true);
        User s = enrollStudent("drill@ulp.edu.vn", "Drill Student");
        completeLessons(s, List.of(a)); // A completed
        progressRepository.saveAndFlush(new LearningProgress(s.getId(), b.getId())); // B in-progress
        // C never opened.

        StudentBreakdown breakdown = breakdownService.getStudentLessonBreakdown(
                clazz.getId(), s.getId(), lecturer.getId(), Role.LECTURER);

        assertThat(breakdown.sections()).hasSize(2);
        assertThat(breakdown.sections().get(0).sectionTitle()).isEqualTo("Chương 1");
        assertThat(breakdown.sections().get(0).lessons())
                .extracting("lessonTitle", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", LearningProgress.STATUS_COMPLETED),
                        org.assertj.core.groups.Tuple.tuple("B", LearningProgress.STATUS_IN_PROGRESS));
        assertThat(breakdown.sections().get(1).lessons())
                .extracting("status").containsExactly(LearningProgress.STATUS_NOT_STARTED);
    }

    @Test
    void drill_down_for_non_member_throws() {
        seedFourPublishedLessons();
        User outsider = ensureUser("outsider@ulp.edu.vn", "Outsider"); // never enrolled

        assertThatThrownBy(() -> breakdownService.getStudentLessonBreakdown(
                clazz.getId(), outsider.getId(), lecturer.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void seedFourPublishedLessons() {
        published = List.of(
                persistLesson(section1.getId(), "Bài 1", (short) 0, true),
                persistLesson(section1.getId(), "Bài 2", (short) 1, true),
                persistLesson(section2.getId(), "Bài 3", (short) 0, true),
                persistLesson(section2.getId(), "Bài 4", (short) 1, true));
    }

    private StudentProgressRow onlyRow(Long userId) {
        return service.getProgressPage(clazz.getId(), lecturer.getId(), Role.LECTURER,
                        "all", "", 0, 10).rows().getContent().stream()
                .filter(r -> r.userId().equals(userId)).findFirst().orElseThrow();
    }

    private void completeLessons(User student, List<Lesson> lessons) {
        for (Lesson l : lessons) {
            LearningProgress lp = new LearningProgress(student.getId(), l.getId());
            lp.markCompleted();
            progressRepository.saveAndFlush(lp);
        }
    }

    private Lesson persistLesson(Long sectionId, String title, short order, boolean publishedFlag) {
        Lesson l = new Lesson(sectionId, title, order, lecturer.getId());
        if (publishedFlag) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private User enrollStudent(String email, String name) {
        User u = ensureUser(email, name);
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
        return u;
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 500);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, Role.STUDENT, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
