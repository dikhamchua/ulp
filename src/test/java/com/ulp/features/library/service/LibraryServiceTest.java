package com.ulp.features.library.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LibraryAsset;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetSectionRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryAssetRow;
import com.ulp.features.library.dto.LibraryDtos.LibraryPickerPage;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.upload.LibraryStorageService;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LibraryService}: upload, rename, owner isolation,
 * and delete-with-reference-guard.
 */
@SpringBootTest
@Transactional
class LibraryServiceTest {

    @Autowired private LibraryService libraryService;
    @Autowired private LibraryAttachTargetsService attachTargetsService;
    @Autowired private LibraryAssetRepository assetRepository;
    @Autowired private LibraryStorageService libraryStorage;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonsService lessonsService;
    @Autowired private LessonAttachmentsService attachmentsService;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private LessonRepository lessonRepository;

    private User lecturer;
    private User otherLecturer;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        otherLecturer = ensureUser("lecturer-lib-other@ulp.edu.vn", "Lib Other", Role.LECTURER);
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

    private MockMultipartFile somePdf(String name) {
        return new MockMultipartFile("file", name, "application/pdf", pdfBytes());
    }

    private MockMultipartFile someMp4(String name) {
        return new MockMultipartFile("file", name, "video/mp4", mp4Bytes());
    }

    @Test
    void upload_document_persists_owned_asset() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("slide.pdf"), "DOCUMENT");

        assertThat(row.id()).isNotNull();
        assertThat(row.kind()).isEqualTo(LibraryAsset.KIND_DOCUMENT);
        assertThat(row.originalFilename()).isEqualTo("slide.pdf");
        LibraryAsset asset = assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId()).orElseThrow();
        assertThat(asset.getStoredPath()).startsWith("library/" + lecturer.getId() + "/");
        assertThat(asset.getOwnerId()).isEqualTo(lecturer.getId());
    }

    @Test
    void upload_rejects_invalid_extension() {
        MockMultipartFile bad = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes());
        assertThatThrownBy(() -> libraryService.upload(lecturer.getId(), bad, "DOCUMENT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rename_updates_title_for_owner_only() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("a.pdf"), null);
        LibraryAssetRow renamed = libraryService.rename(lecturer.getId(), row.id(), "Bài giảng 1");
        assertThat(renamed.title()).isEqualTo("Bài giảng 1");

        assertThatThrownBy(() -> libraryService.rename(otherLecturer.getId(), row.id(), "Hack"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_unreferenced_soft_deletes_and_removes_disk_file() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("free.pdf"), null);
        LibraryAsset asset = assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId()).orElseThrow();
        Path absolute = libraryStorage.resolveAbsolutePath(asset.getStoredPath());
        assertThat(Files.exists(absolute)).isTrue();

        libraryService.delete(lecturer.getId(), row.id());

        assertThat(assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId())).isEmpty();
        assertThat(Files.exists(absolute)).isFalse();
    }

    @Test
    void delete_blocked_when_referenced_by_attachment() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("used.pdf"), null);
        ClassEntity clazz = saveClass("Lib class", lecturer.getId());
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Ch1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "L1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        attachmentsService.bindPdfFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), row.id(),
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> libraryService.delete(lecturer.getId(), row.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId())).isPresent();
    }

    @Test
    void bind_pdf_from_library_switches_content_type_to_pdf() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("main.pdf"), null);
        ClassEntity clazz = saveClass("Lib pdf type", lecturer.getId());
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Ch1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "L-pdf", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        assertThat(lesson.contentType()).isEqualTo(Lesson.CONTENT_TYPE_RICHTEXT);

        LessonAttachmentRow bound = attachmentsService.bindPdfFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), row.id(),
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(lesson.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_PDF);
        assertThat(reloaded.getPdfAttachmentId()).isEqualTo(bound.id());
        assertThat(reloaded.getContentRichtext()).isNull();
    }

    @Test
    void delete_blocked_when_referenced_by_lesson_video() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), someMp4("clip.mp4"), "VIDEO");
        ClassEntity clazz = saveClass("Lib video ref", lecturer.getId());
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Ch1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "V1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.bindVideoFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), row.id(),
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> libraryService.delete(lecturer.getId(), row.id()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId())).isPresent();
    }

    @Test
    void bind_video_from_library_success_and_rejects_document() throws Exception {
        LibraryAssetRow video = libraryService.upload(lecturer.getId(), someMp4("ok.mp4"), "VIDEO");
        LibraryAssetRow doc = libraryService.upload(lecturer.getId(), somePdf("not-video.pdf"), "DOCUMENT");
        ClassEntity clazz = saveClass("Lib bind video", lecturer.getId());
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Ch1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "V-bind", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        Lesson bound = lessonsService.bindVideoFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), video.id(),
                lecturer.getId(), Role.LECTURER);
        assertThat(bound.getVideoLibraryAssetId()).isEqualTo(video.id());
        assertThat(bound.getVideoUrl()).startsWith("library/");
        // Wizard bind must flip content type so student views render the player.
        assertThat(bound.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_VIDEO);
        assertThat(bound.getVideoProvider()).isEqualTo("UPLOAD");

        assertThatThrownBy(() -> lessonsService.bindVideoFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), doc.id(),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
        // Previous library video binding must remain after the rejected DOCUMENT attempt.
        Lesson reloaded = lessonRepository.findById(lesson.id()).orElseThrow();
        assertThat(reloaded.getVideoLibraryAssetId()).isEqualTo(video.id());
        assertThat(reloaded.getContentType()).isEqualTo(Lesson.CONTENT_TYPE_VIDEO);
    }

    @Test
    void picker_lists_only_owner_assets() throws Exception {
        libraryService.upload(lecturer.getId(), somePdf("mine.pdf"), null);
        libraryService.upload(otherLecturer.getId(), somePdf("theirs.pdf"), null);

        LibraryPickerPage mine = libraryService.listForPicker(lecturer.getId(), "", "", 0, 20);
        assertThat(mine.items()).isNotEmpty();
        assertThat(mine.items().stream().map(i -> i.originalFilename()))
                .contains("mine.pdf")
                .doesNotContain("theirs.pdf");
    }

    @Test
    void listSections_creates_default_chuong_1_when_empty() {
        ClassEntity emptyClass = saveClass("Empty secs " + UUID.randomUUID(), lecturer.getId());
        assertThat(sectionRepository.findByClassIdOrderByDisplayOrderAsc(emptyClass.getId())).isEmpty();

        List<AttachTargetSectionRow> first = attachTargetsService.listSections(
                emptyClass.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).title()).isEqualTo("Chương 1");
        assertThat(first.get(0).id()).isNotNull();

        // Second call must not create another default section.
        List<AttachTargetSectionRow> second = attachTargetsService.listSections(
                emptyClass.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(first.get(0).id());
    }

    @Test
    void cascade_delete_lesson_keeps_library_asset() throws Exception {
        LibraryAssetRow row = libraryService.upload(lecturer.getId(), somePdf("keep.pdf"), null);
        ClassEntity clazz = saveClass("Lib cascade", lecturer.getId());
        Section section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Ch1", (short) 0, lecturer.getId()));
        LessonRow lesson = lessonsService.create(
                clazz.getId(), section.getId(), "L1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonAttachmentRow att = attachmentsService.bindAttachmentFromLibrary(
                clazz.getId(), section.getId(), lesson.id(), row.id(),
                lecturer.getId(), Role.LECTURER);
        assertThat(att.id()).isNotNull();

        lessonsService.delete(clazz.getId(), section.getId(), lesson.id(),
                lecturer.getId(), Role.LECTURER);

        assertThat(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lesson.id())).isEmpty();
        assertThat(assetRepository.findByIdAndOwnerId(row.id(), lecturer.getId())).isPresent();
        assertThat(libraryService.countReferences(row.id())).isZero();
    }

    private ClassEntity saveClass(String name, Long lecturerId) {
        String code = "L" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
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
