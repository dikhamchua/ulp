package com.ulp.features.library.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.LessonTemplate;
import com.ulp.entities.LibraryAsset;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonActivityRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.library.dto.LibraryDtos.LessonCloneResult;
import com.ulp.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonsPageView;
import com.ulp.features.library.repository.LessonTemplateRepository;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.upload.LessonAttachmentStorageService;
import com.ulp.features.upload.LessonVideoStorageService;
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
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for lesson templates: save, clone all content types,
 * one-off promote, and owner isolation.
 */
@SpringBootTest
@Transactional
class LessonTemplateServiceTest {

    @Autowired private LessonTemplateService templateService;
    @Autowired private LessonTemplateRepository templateRepository;
    @Autowired private LibraryService libraryService;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonAttachmentsService attachmentsService;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonActivityRepository activityRepository;
    @Autowired private LessonAttachmentStorageService attachmentStorage;
    @Autowired private LessonVideoStorageService videoStorage;

    private User lecturer;
    private User otherLecturer;
    private ClassEntity clazz;
    private Section section;
    private ClassEntity targetClazz;
    private Section targetSection;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        otherLecturer = ensureUser("lecturer-tpl-other@ulp.edu.vn", "Tpl Other", Role.LECTURER);
        clazz = saveClass("Tpl source " + UUID.randomUUID(), lecturer.getId());
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
        targetClazz = saveClass("Tpl target " + UUID.randomUUID(), lecturer.getId());
        targetSection = sectionRepository.saveAndFlush(
                new Section(targetClazz.getId(), "Chương đích", (short) 0, lecturer.getId()));
    }

    private static byte[] pdfBytes() {
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A};
    }

    private static byte[] mp4Bytes() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x20,
                0x66, 0x74, 0x79, 0x70,
                'i', 's', 'o', 'm',
                0x00, 0x00, 0x02, 0x00
        };
    }

    @Test
    void saveFromLesson_richtext_then_clone_creates_draft() {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Bài RT", "DRAFT",
                "<p>Nội dung <b>mẫu</b></p>", lecturer.getId(), Role.LECTURER);

        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);
        assertThat(tpl.title()).isEqualTo("Bài RT");
        assertThat(tpl.contentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);

        LessonCloneResult cloned = templateService.cloneTemplateToSection(
                tpl.id(), targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);

        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);
        assertThat(dest.getContentRichtext()).contains("Nội dung");
        assertThat(dest.getSectionId()).isEqualTo(targetSection.getId());

        List<LessonActivity> acts = activityRepository
                .findByLessonIdOrderByCreatedAtDesc(cloned.lessonId(),
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent();
        assertThat(acts).isNotEmpty();
        assertThat(acts.get(0).getType()).isEqualTo(LessonActivity.TYPE_CREATED);
        assertThat(acts.get(0).getDescription()).containsIgnoringCase("clone");
    }

    @Test
    void saveFromLesson_pdf_library_and_one_off_attachment_promotes() throws Exception {
        LibraryAssetRow pdfLib = libraryService.upload(
                lecturer.getId(),
                new MockMultipartFile("file", "main.pdf", "application/pdf", pdfBytes()),
                "DOCUMENT");
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Bài PDF", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        attachmentsService.bindPdfFromLibrary(
                clazz.getId(), section.getId(), src.id(), pdfLib.id(),
                lecturer.getId(), Role.LECTURER);

        // One-off supplementary attachment (not library-backed).
        MockMultipartFile extra = new MockMultipartFile(
                "file", "note.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00});
        attachmentsService.upload(
                clazz.getId(), section.getId(), src.id(), extra,
                lecturer.getId(), Role.LECTURER);

        long assetsBefore = assetRepository.countByOwnerId(lecturer.getId());
        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);
        assertThat(tpl.contentType()).isEqualTo(Lesson.CONTENT_TYPE_PDF);
        assertThat(tpl.attachmentCount()).isEqualTo(1);
        // One-off DOCX promoted into library.
        assertThat(assetRepository.countByOwnerId(lecturer.getId()))
                .isGreaterThan(assetsBefore);

        LessonTemplate entity = templateRepository
                .findByIdAndOwnerId(tpl.id(), lecturer.getId()).orElseThrow();
        assertThat(entity.getPdfLibraryAssetId()).isEqualTo(pdfLib.id());

        LessonCloneResult cloned = templateService.cloneTemplateToSection(
                tpl.id(), targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);
        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_PDF);
        assertThat(dest.getPdfAttachmentId()).isNotNull();

        LessonAttachment mainPdf = attachmentRepository.findById(dest.getPdfAttachmentId())
                .orElseThrow();
        assertThat(mainPdf.isLibraryBacked()).isTrue();
        assertThat(mainPdf.getLibraryAssetId()).isEqualTo(pdfLib.id());

        List<LessonAttachment> all = attachmentRepository
                .findByLessonIdOrderByUploadedAtAsc(dest.getId());
        assertThat(all).hasSize(2);
        assertThat(all).allMatch(LessonAttachment::isLibraryBacked);
    }

    @Test
    void cloneLesson_promotes_one_off_pdf_without_sharing_path() throws Exception {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "One-off PDF", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        attachmentsService.uploadMainPdf(
                clazz.getId(), section.getId(), src.id(),
                new MockMultipartFile("file", "solo.pdf", "application/pdf", pdfBytes()),
                lecturer.getId(), Role.LECTURER);
        // Form save would flip type after upload; mirror that for a true PDF lesson.
        Lesson source = lessonRepository.findById(src.id()).orElseThrow();
        Long pdfId = source.getPdfAttachmentId();
        source.switchContentTypeTo(Lesson.CONTENT_TYPE_PDF);
        source.setPdfAttachmentId(pdfId);
        lessonRepository.saveAndFlush(source);

        source = lessonRepository.findById(src.id()).orElseThrow();
        LessonAttachment sourcePdf = attachmentRepository.findById(source.getPdfAttachmentId())
                .orElseThrow();
        assertThat(sourcePdf.isLibraryBacked()).isFalse();
        String sourcePath = sourcePdf.getStoredPath();
        Path sourceAbs = attachmentStorage.resolveAbsolutePath(sourcePath);
        assertThat(Files.exists(sourceAbs)).isTrue();

        LessonCloneResult cloned = templateService.cloneLessonToSection(
                clazz.getId(), section.getId(), src.id(),
                targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);

        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_PDF);
        LessonAttachment destPdf = attachmentRepository.findById(dest.getPdfAttachmentId())
                .orElseThrow();
        assertThat(destPdf.isLibraryBacked()).isTrue();
        assertThat(destPdf.getStoredPath()).startsWith("library/");
        assertThat(destPdf.getStoredPath()).isNotEqualTo(sourcePath);
        // Original one-off file still exists (promote copies, does not move).
        assertThat(Files.exists(sourceAbs)).isTrue();
    }

    @Test
    void saveAndClone_external_video_preserves_url() {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Video YT", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.setExternalVideo(
                clazz.getId(), section.getId(), src.id(),
                "YOUTUBE", "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                lecturer.getId(), Role.LECTURER);
        // Persist content type VIDEO via repository after setExternalVideo
        // (setExternalVideo does not flip type — form save would).
        Lesson lesson = lessonRepository.findById(src.id()).orElseThrow();
        lesson.switchContentTypeTo(Lesson.CONTENT_TYPE_VIDEO);
        lesson.setVideoProvider("YOUTUBE");
        lesson.setVideoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        lessonRepository.saveAndFlush(lesson);

        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);
        assertThat(tpl.contentType()).isEqualTo(Lesson.CONTENT_TYPE_VIDEO);

        LessonCloneResult cloned = templateService.cloneTemplateToSection(
                tpl.id(), targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);
        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_VIDEO);
        assertThat(dest.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(dest.getVideoUrl()).contains("youtube");
        assertThat(dest.getVideoLibraryAssetId()).isNull();
    }

    @Test
    void saveAndClone_uploaded_library_video_reuses_asset() throws Exception {
        LibraryAssetRow video = libraryService.upload(
                lecturer.getId(),
                new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4Bytes()),
                "VIDEO");
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Video lib", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.bindVideoFromLibrary(
                clazz.getId(), section.getId(), src.id(), video.id(),
                lecturer.getId(), Role.LECTURER);

        long assetsBefore = assetRepository.countByOwnerId(lecturer.getId());
        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);
        // No extra promote — library video reused by id.
        assertThat(assetRepository.countByOwnerId(lecturer.getId())).isEqualTo(assetsBefore);

        LessonCloneResult cloned = templateService.cloneTemplateToSection(
                tpl.id(), targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);
        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getVideoLibraryAssetId()).isEqualTo(video.id());
        assertThat(dest.getVideoProvider()).isEqualTo("UPLOAD");
    }

    @Test
    void rename_and_softDelete_owner_only() {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Đổi tên", "DRAFT", "<p>x</p>",
                lecturer.getId(), Role.LECTURER);
        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);

        LessonTemplateRow renamed = templateService.rename(
                lecturer.getId(), tpl.id(), "Tên mới");
        assertThat(renamed.title()).isEqualTo("Tên mới");

        assertThatThrownBy(() -> templateService.rename(
                otherLecturer.getId(), tpl.id(), "Hack"))
                .isInstanceOf(EntityNotFoundException.class);

        templateService.softDelete(lecturer.getId(), tpl.id());
        assertThat(templateRepository.findByIdAndOwnerId(tpl.id(), lecturer.getId()))
                .isEmpty();
    }

    @Test
    void clone_forbidden_for_non_owner_template_and_foreign_class() {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Riêng tư", "DRAFT", "<p>x</p>",
                lecturer.getId(), Role.LECTURER);
        LessonTemplateRow tpl = templateService.saveFromLesson(
                clazz.getId(), section.getId(), src.id(),
                lecturer.getId(), Role.LECTURER);

        // Other lecturer cannot see/clone the template.
        assertThatThrownBy(() -> templateService.cloneTemplateToSection(
                tpl.id(), targetClazz.getId(), targetSection.getId(),
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);

        // Other lecturer cannot clone into a class they do not edit.
        ClassEntity foreign = saveClass("Foreign " + UUID.randomUUID(), otherLecturer.getId());
        Section foreignSec = sectionRepository.saveAndFlush(
                new Section(foreign.getId(), "S", (short) 0, otherLecturer.getId()));
        assertThatThrownBy(() -> templateService.cloneTemplateToSection(
                tpl.id(), foreign.getId(), foreignSec.getId(),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cloneLesson_to_section_copies_richtext_as_draft() {
        LessonRow src = lessonsService.create(
                clazz.getId(), section.getId(), "Nguồn", "PUBLISHED",
                "<p>Hello clone</p>", lecturer.getId(), Role.LECTURER);

        LessonCloneResult cloned = templateService.cloneLessonToSection(
                clazz.getId(), section.getId(), src.id(),
                targetClazz.getId(), targetSection.getId(),
                lecturer.getId(), Role.LECTURER);

        Lesson dest = lessonRepository.findById(cloned.lessonId()).orElseThrow();
        assertThat(dest.getStatus()).isEqualTo(Lesson.STATUS_DRAFT);
        assertThat(dest.getTitle()).isEqualTo("Nguồn");
        assertThat(dest.getContentRichtext()).contains("Hello clone");
        assertThat(dest.getId()).isNotEqualTo(src.id());
    }

    @Test
    void listLessons_returns_live_lessons_for_lecturer_with_urls() {
        LessonRow a = lessonsService.create(
                clazz.getId(), section.getId(), "List Me Alpha", "DRAFT", "<p>a</p>",
                lecturer.getId(), Role.LECTURER);
        lessonsService.create(
                targetClazz.getId(), targetSection.getId(), "List Me Beta", "PUBLISHED",
                "<p>b</p>", lecturer.getId(), Role.LECTURER);

        LibraryLessonsPageView view = templateService.listLessons(
                lecturer.getId(), "List Me", null, 0, 20);
        assertThat(view.lessonCount()).isGreaterThanOrEqualTo(2);
        assertThat(view.classId()).isNull();
        assertThat(view.classOptions()).isNotEmpty();
        assertThat(view.page().getContent())
                .extracting(LibraryLessonRow::title)
                .contains("List Me Alpha", "List Me Beta");
        LibraryLessonRow row = view.page().getContent().stream()
                .filter(r -> a.id().equals(r.lessonId()))
                .findFirst()
                .orElseThrow();
        assertThat(row.classId()).isEqualTo(clazz.getId());
        assertThat(row.sectionId()).isEqualTo(section.getId());
        assertThat(row.editUrl()).contains("/lessons/" + a.id() + "/edit");
        assertThat(row.cloneUrl()).contains("/lessons/" + a.id() + "/clone");
        assertThat(row.className()).isEqualTo(clazz.getName());
        assertThat(row.sectionTitle()).isEqualTo(section.getTitle());
    }

    @Test
    void listLessons_filters_by_classId() {
        lessonsService.create(
                clazz.getId(), section.getId(), "Only Source Class", "DRAFT", "<p>a</p>",
                lecturer.getId(), Role.LECTURER);
        lessonsService.create(
                targetClazz.getId(), targetSection.getId(), "Only Target Class", "DRAFT",
                "<p>b</p>", lecturer.getId(), Role.LECTURER);

        LibraryLessonsPageView filtered = templateService.listLessons(
                lecturer.getId(), null, clazz.getId(), 0, 20);
        assertThat(filtered.classId()).isEqualTo(clazz.getId());
        assertThat(filtered.page().getContent())
                .extracting(LibraryLessonRow::title)
                .contains("Only Source Class")
                .doesNotContain("Only Target Class");
        assertThat(filtered.page().getContent())
                .allMatch(r -> clazz.getId().equals(r.classId()));
    }

    @Test
    void listLessons_ignores_foreign_classId_filter() {
        lessonsService.create(
                clazz.getId(), section.getId(), "Visible Lesson", "DRAFT", "<p>a</p>",
                lecturer.getId(), Role.LECTURER);
        ClassEntity foreign = saveClass("Foreign filter " + UUID.randomUUID(), otherLecturer.getId());

        LibraryLessonsPageView view = templateService.listLessons(
                lecturer.getId(), "Visible Lesson", foreign.getId(), 0, 20);
        // Foreign classId is dropped → behaves like "all classes".
        assertThat(view.classId()).isNull();
        assertThat(view.page().getContent())
                .extracting(LibraryLessonRow::title)
                .contains("Visible Lesson");
    }

    private ClassEntity saveClass(String name, Long lecturerId) {
        String code = "T" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "X");
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
