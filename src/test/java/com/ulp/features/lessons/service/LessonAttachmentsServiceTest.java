package com.ulp.features.lessons.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonAttachmentsService.DownloadHandle;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LessonAttachmentsService}. Boots a fresh
 * class + section + lesson per test so state never bleeds across cases.
 */
@SpringBootTest
@Transactional
class LessonAttachmentsServiceTest {

    @Autowired private LessonAttachmentsService attachmentsService;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonsPublishService publishService;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private User otherLecturer;
    private User student;
    private ClassEntity clazz;
    private Section section;
    private Long lessonId;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        otherLecturer = ensureUser("lecturer-other@ulp.edu.vn", "Lecturer Other", Role.LECTURER);
        student = ensureUser("student-attach@ulp.edu.vn", "Student A", Role.STUDENT);
        clazz = saveClass("Attachments class", lecturer.getId(), "ATTCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonId = lesson.id();
    }

    private static byte[] pdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A};
    }

    private MockMultipartFile somePdf() {
        return new MockMultipartFile("file", "handout.pdf", "application/pdf", pdfBytes());
    }

    @Test
    void upload_happy_path_returns_row_and_creates_db_record() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);

        assertThat(row.id()).isNotNull();
        assertThat(row.originalFilename()).isEqualTo("handout.pdf");
        assertThat(row.downloadUrl()).contains("/api/lessons/" + lessonId + "/attachments/");
        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).hasSize(1);
    }

    @Test
    void upload_blocked_for_non_owner_lecturer() {
        assertThatThrownBy(() -> attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).isEmpty();
    }

    @Test
    void upload_with_cross_class_section_returns_404() {
        // A section that belongs to a different class — should be blocked.
        ClassEntity foreign = saveClass("Other class", otherLecturer.getId(), "OTHCLS");
        Section foreignSection = sectionRepository.saveAndFlush(
                new Section(foreign.getId(), "Foreign", (short) 0, otherLecturer.getId()));

        assertThatThrownBy(() -> attachmentsService.upload(
                clazz.getId(), foreignSection.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_removes_row_and_on_disk_file() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);

        LessonAttachment saved = attachmentRepository.findById(row.id()).orElseThrow();
        var absolute = java.nio.file.Paths.get(System.getProperty("user.dir"), "uploads", saved.getStoredPath());
        assertThat(Files.exists(absolute)).isTrue();

        attachmentsService.delete(clazz.getId(), section.getId(), lessonId, row.id(),
                lecturer.getId(), Role.LECTURER);

        assertThat(attachmentRepository.findById(row.id())).isEmpty();
        assertThat(Files.exists(absolute)).isFalse();
    }

    @Test
    void delete_all_by_lesson_clears_every_row_and_file() throws Exception {
        attachmentsService.upload(clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);
        attachmentsService.upload(clazz.getId(), section.getId(), lessonId,
                new MockMultipartFile("file", "h2.pdf", "application/pdf", pdfBytes()),
                lecturer.getId(), Role.LECTURER);

        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).hasSize(2);
        attachmentsService.deleteAllByLesson(lessonId);
        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).isEmpty();
    }

    @Test
    void lesson_soft_delete_cascades_attachments() throws Exception {
        attachmentsService.upload(clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);
        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).hasSize(1);

        lessonsService.delete(clazz.getId(), section.getId(), lessonId,
                lecturer.getId(), Role.LECTURER);

        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId)).isEmpty();
    }

    @Test
    void download_allowed_for_lecturer_even_in_draft() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);

        DownloadHandle handle = attachmentsService.download(
                lessonId, row.id(), lecturer.getId(), Role.LECTURER);
        assertThat(handle.originalFilename()).isEqualTo("handout.pdf");
        assertThat(Files.exists(handle.absolutePath())).isTrue();
    }

    @Test
    void download_blocked_for_student_when_lesson_is_draft() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);
        enrollStudent();

        assertThatThrownBy(() -> attachmentsService.download(
                lessonId, row.id(), student.getId(), Role.STUDENT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void download_allowed_for_enrolled_student_when_lesson_is_published() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);
        publishService.publish(clazz.getId(), section.getId(), lessonId,
                lecturer.getId(), Role.LECTURER);
        enrollStudent();

        DownloadHandle handle = attachmentsService.download(
                lessonId, row.id(), student.getId(), Role.STUDENT);
        assertThat(handle.sizeBytes()).isGreaterThan(0);
    }

    @Test
    void download_blocked_for_non_enrolled_student_even_when_published() throws Exception {
        LessonAttachmentRow row = attachmentsService.upload(
                clazz.getId(), section.getId(), lessonId, somePdf(),
                lecturer.getId(), Role.LECTURER);
        publishService.publish(clazz.getId(), section.getId(), lessonId,
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> attachmentsService.download(
                lessonId, row.id(), student.getId(), Role.STUDENT))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private void enrollStudent() {
        Enrollment e = Enrollment.createFor(student, clazz.getId(),
                Enrollment.JoinedVia.CODE, null);
        enrollmentRepository.saveAndFlush(e);
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
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    name, role, true, null, null);
            return userRepository.saveAndFlush(u);
        });
    }
}
