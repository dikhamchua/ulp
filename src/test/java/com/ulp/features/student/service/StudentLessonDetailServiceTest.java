package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonAttachmentRow;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
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
 * Integration tests for {@link StudentLessonDetailService#getLessonDetail} (ULP-4.2).
 * Boots the full Spring context with MySQL so {@code @SQLRestriction} on
 * {@link Lesson} / {@link ClassEntity} is exercised end-to-end.
 */
@SpringBootTest
@Transactional
class StudentLessonDetailServiceTest {

    private static final String NF_MSG = "Class not found or not accessible";

    @Autowired private StudentLessonDetailService studentLessonDetailService;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonAttachmentRepository lessonAttachmentRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        student = ensureUser("student-lesson-detail@ulp.edu.vn", "Student Detail");
        clazz = saveClass("Lesson detail class", "SLDCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
    }

    @Test
    void happy_path_returns_populated_view_with_attachments() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>Nội dung</p>", true);
        LessonAttachment a1 = persistAttachment(lesson.getId(), "slides.pdf", "application/pdf", 1024L);
        LessonAttachment a2 = persistAttachment(lesson.getId(), "handout.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2048L);
        enrollActive();

        LessonDetailView view = studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId());

        assertThat(view.classId()).isEqualTo(clazz.getId());
        assertThat(view.className()).isEqualTo("Lesson detail class");
        assertThat(view.lessonId()).isEqualTo(lesson.getId());
        assertThat(view.lessonTitle()).isEqualTo("Bài 1");
        assertThat(view.sectionId()).isEqualTo(section.getId());
        assertThat(view.sectionTitle()).isEqualTo("Chương 1");
        assertThat(view.contentRichtext()).isEqualTo("<p>Nội dung</p>");
        assertThat(view.publishedAt()).isNotNull();
        assertThat(view.attachments()).hasSize(2);
        // Exact download URL format (D7) — required by spec scenario.
        LessonAttachmentRow row1 = view.attachments().get(0);
        assertThat(row1.downloadUrl())
                .isEqualTo("/api/lessons/" + lesson.getId() + "/attachments/" + a1.getId() + "/download");
        assertThat(row1.filename()).isEqualTo("slides.pdf");
        assertThat(row1.sizeBytes()).isEqualTo(1024L);
        LessonAttachmentRow row2 = view.attachments().get(1);
        assertThat(row2.downloadUrl())
                .isEqualTo("/api/lessons/" + lesson.getId() + "/attachments/" + a2.getId() + "/download");
    }

    @Test
    void empty_content_returns_null_or_empty_richtext() {
        Lesson lesson = persistLesson(section.getId(), "Bài rỗng nội dung", null, true);
        enrollActive();

        LessonDetailView view = studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId());

        assertThat(view.contentRichtext()).isNullOrEmpty();
        assertThat(view.attachments()).isEmpty();
    }

    @Test
    void no_attachments_returns_empty_list() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>Body</p>", true);
        enrollActive();

        LessonDetailView view = studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId());

        assertThat(view.attachments()).isNotNull().isEmpty();
    }

    @Test
    void not_enrolled_user_gets_404() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>X</p>", true);

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void removed_enrollment_gets_404() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>X</p>", true);
        Enrollment e = enrollActive();
        e.markRemoved();
        enrollmentRepository.saveAndFlush(e);

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void completed_enrollment_gets_404() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>X</p>", true);
        Enrollment e = enrollActive();
        forceEnrollmentStatus(e.getId(), Enrollment.STATUS_COMPLETED);

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void draft_lesson_is_hidden_from_enrolled_student() {
        Lesson lesson = persistLesson(section.getId(), "Bài nháp", "<p>X</p>", false);
        enrollActive();

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void soft_deleted_lesson_gets_404() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>X</p>", true);
        enrollActive();
        lesson.markDeleted();
        lessonRepository.saveAndFlush(lesson);
        entityManager.clear();

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void cross_class_lesson_gets_404() {
        // Lesson L belongs to class B; the caller is ACTIVE-enrolled only in A.
        ClassEntity classB = saveClass("Other class", "SLDOTH");
        Section sectionB = sectionRepository.saveAndFlush(
                new Section(classB.getId(), "Chương B", (short) 0, lecturer.getId()));
        Lesson lessonInB = persistLesson(sectionB.getId(), "Bài B", "<p>X</p>", true);
        enrollActive();

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lessonInB.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    @Test
    void soft_deleted_class_gets_404_even_for_active_enrollment() {
        Lesson lesson = persistLesson(section.getId(), "Bài 1", "<p>X</p>", true);
        enrollActive();
        clazz.softDelete();
        classRepository.saveAndFlush(clazz);
        // Clear so the next findById re-reads and @SQLRestriction applies.
        entityManager.clear();

        assertThatThrownBy(() -> studentLessonDetailService.getLessonDetail(
                clazz.getId(), lesson.getId(), student.getId()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage(NF_MSG);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Lesson persistLesson(Long sectionId, String title, String content, boolean published) {
        Lesson l = new Lesson(sectionId, title, (short) 0, lecturer.getId());
        if (content != null) l.updateContent(content);
        if (published) l.publish();
        return lessonRepository.saveAndFlush(l);
    }

    private LessonAttachment persistAttachment(Long lessonId, String filename,
                                               String mime, long size) {
        LessonAttachment att = new LessonAttachment(
                lessonId, filename, "stored/" + filename, mime, size, lecturer.getId());
        return lessonAttachmentRepository.saveAndFlush(att);
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
