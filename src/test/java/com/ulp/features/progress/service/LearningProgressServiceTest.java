package com.ulp.features.progress.service;

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
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.service.StudentLessonsService;
import com.ulp.security.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LearningProgressService} (ULP-4.5). Also
 * exercises {@link StudentLessonsService} aggregates so DRAFT-exclusion is
 * covered end-to-end against MySQL.
 */
@SpringBootTest
@Transactional
class LearningProgressServiceTest {

    @Autowired private LearningProgressService progressService;
    @Autowired private LearningProgressRepository progressRepository;
    @Autowired private StudentLessonsService studentLessonsService;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;
    private short orderSeq;

    @BeforeEach
    void setUp() {
        orderSeq = 0;
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = ensureUser("student-progress@ulp.edu.vn", "Student Progress");
        clazz = saveClass("Progress class", "PRGCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
    }

    @Test
    void first_open_creates_in_progress() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getStartedAt()).isNotNull();
        assertThat(p.getCompletedAt()).isNull();
    }

    @Test
    void reopen_is_idempotent() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());
        LearningProgress first = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        // Still IN_PROGRESS, same started_at, exactly one row for the pair.
        LearningProgress again = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
    }

    @Test
    void toggle_marks_completed() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();
        progressService.recordOpened(clazz.getId(), lesson.getId(), student.getId());

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isTrue();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_COMPLETED);
        assertThat(p.getCompletedAt()).isNotNull();
        assertThat(p.getProgressPercent()).isEqualByComparingTo("100");
    }

    @Test
    void toggle_unmarks_completed() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();
        progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isFalse();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_IN_PROGRESS);
        assertThat(p.getCompletedAt()).isNull();
        assertThat(p.getProgressPercent()).isEqualByComparingTo("0");
    }

    @Test
    void toggle_without_prior_open_creates_completed() {
        Lesson lesson = persistLesson("Bài 1", true);
        enrollActive();

        boolean now = progressService.toggleCompletion(clazz.getId(), lesson.getId(), student.getId());

        assertThat(now).isTrue();
        LearningProgress p = progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(LearningProgress.STATUS_COMPLETED);
        assertThat(p.getStartedAt()).isNotNull();
    }

    @Test
    void non_enrolled_open_denied() {
        Lesson lesson = persistLesson("Bài 1", true);

        assertThatThrownBy(() -> progressService.recordOpened(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(progressRepository
                .findByUserIdAndLessonId(student.getId(), lesson.getId())).isEmpty();
    }

    @Test
    void draft_lesson_toggle_denied() {
        Lesson draft = persistLesson("Bài nháp", false);
        enrollActive();

        assertThatThrownBy(() -> progressService.toggleCompletion(
                clazz.getId(), draft.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(progressRepository
                .findByUserIdAndLessonId(student.getId(), draft.getId())).isEmpty();
    }

    @Test
    void aggregates_exclude_draft_lessons() {
        Lesson pub1 = persistLesson("Bài 1", true);
        persistLesson("Bài 2", true);
        persistLesson("Bài nháp", false); // DRAFT must not count
        enrollActive();
        progressService.toggleCompletion(clazz.getId(), pub1.getId(), student.getId());
        entityManager.flush();
        entityManager.clear();

        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId());

        assertThat(view.publishedTotal()).isEqualTo(2);
        assertThat(view.completedTotal()).isEqualTo(1);
        assertThat(view.percent()).isEqualTo(50);
        assertThat(view.sections().get(0).completedCount()).isEqualTo(1);
        assertThat(view.sections().get(0).publishedCount()).isEqualTo(2);
    }

    @Test
    void empty_class_reports_zero_percent() {
        enrollActive();

        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId());

        assertThat(view.publishedTotal()).isZero();
        assertThat(view.percent()).isZero();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Lesson persistLesson(String title, boolean published) {
        Lesson l = new Lesson(section.getId(), title, orderSeq++, lecturer.getId());
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private Enrollment enrollActive() {
        return enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
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

    private User ensureUser(String email, String name) {
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, Role.STUDENT, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
