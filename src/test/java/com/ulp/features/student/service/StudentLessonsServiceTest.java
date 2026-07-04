package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.dto.StudentLessonsDtos.StudentLessonRow;
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

/** Integration tests for {@link StudentLessonsService}. */
@SpringBootTest
@Transactional
class StudentLessonsServiceTest {

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
    private Section section1;
    private Section section2;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = ensureUser("student-lessons@ulp.edu.vn", "Student Lessons");
        clazz = saveClass("Student lessons class", "STLSN1");
        section1 = sectionRepository.saveAndFlush(new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        section2 = sectionRepository.saveAndFlush(new Section(clazz.getId(), "Chương 2", (short) 1, lecturer.getId()));
    }

    @Test
    void enrolled_student_sees_published_lessons_only() {
        Lesson pub1 = persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        Lesson pub2 = persistLesson(section1.getId(), "Bài 2", (short) 1, true);
        Lesson draft = persistLesson(section1.getId(), "Bài nháp", (short) 2, false);
        enrollActive();

        ClassLessonsView view = studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.className()).isEqualTo("Student lessons class");
        assertThat(view.sections()).hasSize(2);
        SectionWithLessons first = view.sections().get(0);
        assertThat(first.lessons()).extracting(StudentLessonRow::id)
                .containsExactly(pub1.getId(), pub2.getId()).doesNotContain(draft.getId());
        // Second section still appears with an empty lessons list.
        assertThat(view.sections().get(1).lessons()).isEmpty();
    }

    @Test
    void not_enrolled_user_gets_404() {
        persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        assertThatThrownBy(() -> studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void owning_lecturer_not_enrolled_can_list() {
        Lesson pub = persistLesson(section1.getId(), "Bài 1", (short) 0, true);

        // The owning lecturer is not enrolled, yet must reach the thread to moderate.
        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), lecturer.getId(), Role.LECTURER);

        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.sections().get(0).lessons())
                .extracting(StudentLessonRow::id).containsExactly(pub.getId());
    }

    @Test
    void admin_not_enrolled_can_list() {
        Lesson pub = persistLesson(section1.getId(), "Bài 1", (short) 0, true);

        // ADMIN bypasses enrollment (design D7); student here stands in as a
        // non-enrolled, non-owning caller whose role is elevated to ADMIN.
        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.ADMIN);

        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.sections().get(0).lessons())
                .extracting(StudentLessonRow::id).containsExactly(pub.getId());
    }

    @Test
    void head_not_enrolled_can_list() {
        Lesson pub = persistLesson(section1.getId(), "Bài 1", (short) 0, true);

        ClassLessonsView view = studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.HEAD);

        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.sections().get(0).lessons())
                .extracting(StudentLessonRow::id).containsExactly(pub.getId());
    }

    @Test
    void non_enrolled_non_moderator_student_gets_404() {
        persistLesson(section1.getId(), "Bài 1", (short) 0, true);

        // A plain STUDENT who is neither enrolled nor the owner is still blocked.
        assertThatThrownBy(() -> studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.STUDENT))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void soft_deleted_class_gets_404_for_admin_moderator() {
        clazz.softDelete();
        classRepository.saveAndFlush(clazz);
        entityManager.clear();

        // Lesson gates run first, so ADMIN gains nothing on a deleted class.
        assertThatThrownBy(() -> studentLessonsService
                .listClassLessons(clazz.getId(), student.getId(), Role.ADMIN))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void removed_enrollment_gets_404() {
        Enrollment e = enrollActive();
        e.markRemoved();
        enrollmentRepository.saveAndFlush(e);
        assertThatThrownBy(() -> studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void completed_enrollment_gets_404() {
        Enrollment e = enrollActive();
        // Native UPDATE — Enrollment exposes no setter for COMPLETED.
        forceEnrollmentStatus(e.getId(), Enrollment.STATUS_COMPLETED);
        assertThatThrownBy(() -> studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void soft_deleted_class_gets_404_for_enrolled_student() {
        enrollActive();
        clazz.softDelete();
        classRepository.saveAndFlush(clazz);
        // Clear so the next findById is re-read and SQLRestriction applies.
        entityManager.clear();
        assertThatThrownBy(() -> studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void soft_deleted_section_is_hidden() {
        persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        enrollActive();
        section2.markDeleted();
        sectionRepository.saveAndFlush(section2);

        ClassLessonsView view = studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.sections()).hasSize(1);
        assertThat(view.sections().get(0).sectionId()).isEqualTo(section1.getId());
    }

    @Test
    void soft_deleted_lesson_is_hidden() {
        Lesson published = persistLesson(section1.getId(), "Bài 1", (short) 0, true);
        enrollActive();
        published.markDeleted();
        lessonRepository.saveAndFlush(published);

        ClassLessonsView view = studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.sections().get(0).lessons()).isEmpty();
    }

    @Test
    void empty_class_returns_empty_sections() {
        enrollActive();
        section1.markDeleted();
        section2.markDeleted();
        sectionRepository.saveAndFlush(section1);
        sectionRepository.saveAndFlush(section2);

        ClassLessonsView view = studentLessonsService.listClassLessons(clazz.getId(), student.getId(), Role.STUDENT);

        assertThat(view.sections()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Lesson persistLesson(Long sectionId, String title, short order, boolean published) {
        Lesson l = new Lesson(sectionId, title, order, lecturer.getId());
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private Enrollment enrollActive() {
        return enrollmentRepository.saveAndFlush(Enrollment.createFor(
                student, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private void forceEnrollmentStatus(Long enrollmentId, String status) {
        entityManager.createNativeQuery("UPDATE enrollments SET status = :s WHERE id = :id")
                .setParameter("s", status).setParameter("id", enrollmentId).executeUpdate();
        entityManager.flush();
        entityManager.clear();
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
