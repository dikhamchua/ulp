package com.ulp.features.lessons.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.PublicViewToken;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.PublicViewTokenRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.PublicViewTokenService.AttachmentHandle;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.service.LibraryService;
import com.ulp.security.Role;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link PublicViewTokenService}. Boots the full Spring
 * context with MySQL so the FK from {@code public_view_tokens.attachment_id}
 * to {@code lesson_attachments} and the token TTL/cleanup logic are exercised
 * end-to-end. Covers create → resolve → expiry → scheduled cleanup (ULP-4.x).
 */
@SpringBootTest
@Transactional
class PublicViewTokenServiceTest {

    @Autowired private PublicViewTokenService tokenService;
    @Autowired private PublicViewTokenRepository tokenRepository;
    @Autowired private LessonAttachmentRepository lessonAttachmentRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LibraryService libraryService;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonAttachmentsService attachmentsService;
    @Autowired private EntityManager entityManager;

    private User lecturer;
    private LessonAttachment attachment;
    private ClassEntity clazz;
    private Section section;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Token test class", "PVTCLS");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        Lesson lesson = new Lesson(section.getId(), "Bài có tệp", (short) 0, lecturer.getId());
        lesson.updateContent("");
        lesson = lessonRepository.saveAndFlush(lesson);
        attachment = lessonAttachmentRepository.saveAndFlush(new LessonAttachment(
                lesson.getId(), "slides.pptx", "stored/slides.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                4096L, lecturer.getId()));
    }

    private static byte[] pdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A};
    }

    @Test
    void createPublicViewUrl_persists_token_and_returns_public_url() {
        String url = tokenService.createPublicViewUrl(attachment.getId());

        assertThat(url).contains("/public/view/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        // UUID without dashes → 32 hex chars.
        assertThat(token).hasSize(32).matches("[0-9a-f]{32}");

        PublicViewToken persisted = tokenRepository.findByToken(token).orElseThrow();
        assertThat(persisted.getAttachmentId()).isEqualTo(attachment.getId());
        // Default TTL is 1 hour; allow a small clock skew window.
        assertThat(persisted.getExpiresAt())
                .isAfter(LocalDateTime.now().plusMinutes(55))
                .isBefore(LocalDateTime.now().plusMinutes(65));
    }

    @Test
    void resolve_valid_token_returns_attachment_handle() {
        String url = tokenService.createPublicViewUrl(attachment.getId());
        String token = url.substring(url.lastIndexOf('/') + 1);

        AttachmentHandle handle = tokenService.resolve(token);

        assertThat(handle.originalFilename()).isEqualTo("slides.pptx");
        assertThat(handle.mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        assertThat(handle.sizeBytes()).isEqualTo(4096L);
        assertThat(handle.absolutePath()).isNotNull();
        assertThat(handle.absolutePath().toString()).endsWith("slides.pptx");
    }

    @Test
    void resolve_unknown_token_throws() {
        assertThatThrownBy(() -> tokenService.resolve("deadbeefdeadbeefdeadbeefdeadbeef"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Invalid token");
    }

    @Test
    void resolve_expired_token_throws_and_deletes_it() {
        PublicViewToken expired = tokenRepository.saveAndFlush(new PublicViewToken(
                attachment.getId(), "expiredtokenexpiredtokenexpired0",
                LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> tokenService.resolve(expired.getToken()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Token expired");

        // The expired row is purged on access.
        entityManager.flush();
        entityManager.clear();
        assertThat(tokenRepository.findByToken(expired.getToken())).isEmpty();
    }

    @Test
    void cleanupExpired_removes_only_expired_tokens() {
        PublicViewToken expired = tokenRepository.saveAndFlush(new PublicViewToken(
                attachment.getId(), "expiredsweeptokenexpiredsweep000",
                LocalDateTime.now().minusHours(2)));
        PublicViewToken valid = tokenRepository.saveAndFlush(new PublicViewToken(
                attachment.getId(), "validsweeptokenvalidsweeptoken00",
                LocalDateTime.now().plusHours(1)));

        int deleted = tokenService.cleanupExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        entityManager.clear();
        assertThat(tokenRepository.findByToken(expired.getToken())).isEmpty();
        assertThat(tokenRepository.findByToken(valid.getToken())).isPresent();
    }

    @Test
    void resolve_library_backed_attachment_uses_library_root_and_existing_file() throws Exception {
        LibraryAssetRow asset = libraryService.upload(
                lecturer.getId(),
                new MockMultipartFile("file", "lib-slides.pdf", "application/pdf", pdfBytes()),
                "DOCUMENT");
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "Bài library view", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonAttachmentRow bound = attachmentsService.bindAttachmentFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), asset.id(),
                lecturer.getId(), Role.LECTURER);

        String url = tokenService.createPublicViewUrl(bound.id());
        String token = url.substring(url.lastIndexOf('/') + 1);

        AttachmentHandle handle = tokenService.resolve(token);

        assertThat(handle.originalFilename()).isEqualTo("lib-slides.pdf");
        assertThat(handle.mimeType()).isEqualTo("application/pdf");
        assertThat(handle.absolutePath().toString()).contains("library");
        assertThat(Files.exists(handle.absolutePath())).isTrue();
        assertThat(Files.readAllBytes(handle.absolutePath())).startsWith("%PDF-".getBytes());
    }

    private ClassEntity saveClass(String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        // Unique code per run to avoid collisions with leftover rows.
        entity.setCode(code + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x" + UUID.randomUUID().toString().substring(0, 3));
            return classRepository.saveAndFlush(entity);
        }
    }
}
