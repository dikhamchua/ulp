package com.ulp.features.lessons.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.LessonDtos.LessonForm;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonActivityRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link LessonsService}. Mirrors
 * {@code SectionsServiceTest}: every test boots a fresh class + section
 * owned by the seeded lecturer so state never bleeds across tests.
 */
@SpringBootTest
@Transactional
class LessonsServiceTest {

    @Autowired private LessonsService lessonsService;
    @Autowired private LessonsPublishService lessonsPublishService;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonActivityRepository activityRepository;
    @Autowired private LessonAttachmentRepository attachmentRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;

    private User lecturer;
    private User otherLecturer;
    private ClassEntity clazz;
    private Section section;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        otherLecturer = ensureExtraLecturer();
        clazz = saveClass("Lessons IT class", lecturer.getId(), "LSNIT");
        section = sectionRepository.saveAndFlush(
                new Section(clazz.getId(), "Chương 1", (short) 0, lecturer.getId()));
    }

    @Test
    void create_two_lessons_returns_rows_with_increasing_display_order() {
        LessonRow first = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonRow second = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 2", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        assertThat(first.displayOrder()).isEqualTo((short) 0);
        assertThat(second.displayOrder()).isEqualTo((short) 1);

        List<LessonRow> listed = lessonsService.listForSection(
                clazz.getId(), section.getId(),
                lecturer.getId(), Role.LECTURER);
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).title()).isEqualTo("Bài 1");
        assertThat(listed.get(1).title()).isEqualTo("Bài 2");
    }

    @Test
    void update_changes_title_and_writes_updated_activity() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "Cũ", "DRAFT", "<p>x</p>",
                lecturer.getId(), Role.LECTURER);

        lessonsService.update(clazz.getId(), section.getId(), row.id(),
                "Mới", "DRAFT", "<p>x</p>",
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Mới");

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        // Newest first: UPDATED then CREATED.
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(LessonActivity.TYPE_UPDATED);
        assertThat(page.getContent().get(0).getMetadata()).contains("\"old\":\"Cũ\"")
                .contains("\"new\":\"Mới\"");
    }

    @Test
    void update_with_unchanged_fields_does_not_pollute_history() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "Unchanged", "DRAFT", "<p>same</p>",
                lecturer.getId(), Role.LECTURER);

        lessonsService.update(clazz.getId(), section.getId(), row.id(),
                "Unchanged", "DRAFT", "<p>same</p>",
                lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(LessonActivity.TYPE_CREATED);
    }

    @Test
    void update_sanitises_script_in_body() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "T", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        lessonsService.update(clazz.getId(), section.getId(), row.id(),
                "T", "DRAFT", "<p>OK</p><script>x()</script>",
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentRichtext()).contains("<p>OK</p>");
        assertThat(reloaded.getContentRichtext()).doesNotContain("<script>");
    }

    @Test
    void publish_changes_status_and_writes_published_activity() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "T", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsPublishService.publish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("PUBLISHED");
        assertThat(reloaded.getPublishedAt()).isNotNull();

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(LessonActivity::getType)
                .contains(LessonActivity.TYPE_PUBLISHED);
    }

    @Test
    void unpublish_changes_status_and_writes_unpublished_activity() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "T", "PUBLISHED", "",
                lecturer.getId(), Role.LECTURER);
        lessonsPublishService.unpublish(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DRAFT");
        assertThat(reloaded.getPublishedAt()).isNull();

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(LessonActivity::getType)
                .contains(LessonActivity.TYPE_UNPUBLISHED);
    }

    @Test
    void delete_clears_display_order_and_writes_deleted_activity() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "X", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        lessonsService.delete(clazz.getId(), section.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        // The default repository list method filters soft-deleted rows via
        // @SQLRestriction. findById bypasses the restriction when the row
        // is already cached in the persistence context, so we verify the
        // delete by checking the list does not include it.
        List<LessonRow> listed = lessonsService.listForSection(
                clazz.getId(), section.getId(),
                lecturer.getId(), Role.LECTURER);
        assertThat(listed).extracting(LessonRow::id).doesNotContain(row.id());

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(LessonActivity.TYPE_DELETED);
    }

    @Test
    void create_after_delete_does_not_collide_with_unique_key() {
        LessonRow first = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 1", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonRow second = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 2", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        lessonsService.delete(clazz.getId(), section.getId(), second.id(),
                lecturer.getId(), Role.LECTURER);

        LessonRow recreated = lessonsService.create(
                clazz.getId(), section.getId(), "Bài 2 mới", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        assertThat(recreated.displayOrder()).isEqualTo((short) 1);
        List<LessonRow> listed = lessonsService.listForSection(
                clazz.getId(), section.getId(),
                lecturer.getId(), Role.LECTURER);
        assertThat(listed).extracting(LessonRow::id)
                .containsExactly(first.id(), recreated.id());
    }

    @Test
    void reorder_writes_reordered_activity_only_for_moved_lessons() {
        LessonRow a = lessonsService.create(
                clazz.getId(), section.getId(), "A", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonRow b = lessonsService.create(
                clazz.getId(), section.getId(), "B", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonRow c = lessonsService.create(
                clazz.getId(), section.getId(), "C", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        // Swap A and C, keep B in the middle.
        lessonsService.reorder(clazz.getId(), section.getId(),
                Arrays.asList(c.id(), b.id(), a.id()),
                lecturer.getId(), Role.LECTURER);

        var aPage = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                a.id(), PageRequest.of(0, 10));
        assertThat(aPage.getContent()).extracting(LessonActivity::getType)
                .containsExactly(LessonActivity.TYPE_REORDERED,
                                 LessonActivity.TYPE_CREATED);

        var bPage = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                b.id(), PageRequest.of(0, 10));
        // B didn't move — only CREATED, no REORDERED row.
        assertThat(bPage.getContent()).extracting(LessonActivity::getType)
                .containsExactly(LessonActivity.TYPE_CREATED);

        var cPage = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                c.id(), PageRequest.of(0, 10));
        assertThat(cPage.getContent()).extracting(LessonActivity::getType)
                .containsExactly(LessonActivity.TYPE_REORDERED,
                                 LessonActivity.TYPE_CREATED);
    }

    @Test
    void reorder_with_wrong_ids_throws_illegal_argument() {
        LessonRow a = lessonsService.create(
                clazz.getId(), section.getId(), "A", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonRow b = lessonsService.create(
                clazz.getId(), section.getId(), "B", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        // Missing id — mismatch.
        assertThatThrownBy(() -> lessonsService.reorder(
                clazz.getId(), section.getId(), List.of(a.id()),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);

        // Unknown id.
        Long bogus = b.id() + 999_999L;
        assertThatThrownBy(() -> lessonsService.reorder(
                clazz.getId(), section.getId(),
                Arrays.asList(a.id(), bogus),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_owner_lecturer_cannot_create_or_update_or_delete() {
        assertThatThrownBy(() -> lessonsService.create(
                clazz.getId(), section.getId(), "Lậu", "DRAFT", "",
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        // Seed a lesson via the owner so we have something to attack.
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "Owned", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);

        assertThatThrownBy(() -> lessonsService.update(
                clazz.getId(), section.getId(), row.id(),
                "Hijacked", "DRAFT", "",
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> lessonsService.delete(
                clazz.getId(), section.getId(), row.id(),
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Content-type dispatch + cleanup coverage ──────────────────────

    @Test
    void create_richtext_persists_sanitised_body() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "RT", "DRAFT",
                "<p>OK</p><script>x()</script>",
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("RICHTEXT");
        assertThat(reloaded.getContentRichtext()).contains("<p>OK</p>");
        assertThat(reloaded.getContentRichtext()).doesNotContain("<script>");
    }

    @Test
    void create_pdf_requires_pre_uploaded_attachment_id() {
        // Creating a brand-new lesson always lands as RICHTEXT; switching
        // to PDF on the next save requires the lecturer to upload first.
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "PDF lesson", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        LessonForm form = new LessonForm(
                "PDF lesson", "DRAFT", "", "PDF", null, null);

        assertThatThrownBy(() -> lessonsService.update(
                clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF chưa được tải lên");
    }

    @Test
    void create_video_youtube_validates_url() {
        // The service-level switch requires non-blank URL + provider; the
        // regex validation happens in the API controller layer.
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "VID lesson", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.setExternalVideo(clazz.getId(), section.getId(), row.id(),
                "YOUTUBE", "https://www.youtube.com/watch?v=abc123",
                lecturer.getId(), Role.LECTURER);
        LessonForm form = new LessonForm(
                "VID lesson", "DRAFT", "", "VIDEO",
                "https://www.youtube.com/watch?v=abc123", "YOUTUBE");

        lessonsService.update(clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("VIDEO");
        assertThat(reloaded.getVideoProvider()).isEqualTo("YOUTUBE");
        assertThat(reloaded.getVideoUrl()).contains("youtube.com/watch?v=abc123");
        assertThat(reloaded.getContentRichtext()).isNull();
    }

    @Test
    void create_video_upload_stores_path() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "UPL lesson", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.setUploadedVideo(clazz.getId(), section.getId(), row.id(),
                "lessons/" + row.id() + "/video/abc.mp4",
                lecturer.getId(), Role.LECTURER);
        LessonForm form = new LessonForm(
                "UPL lesson", "DRAFT", "", "VIDEO",
                "lessons/" + row.id() + "/video/abc.mp4", "UPLOAD");

        lessonsService.update(clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(reloaded.getVideoUrl()).startsWith("lessons/");
    }

    @Test
    void update_switch_richtext_to_pdf_nulls_body() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "Mixed", "DRAFT", "<p>Body</p>",
                lecturer.getId(), Role.LECTURER);
        // Seed a PDF attachment row + bind as main PDF before switching.
        Lesson lesson = lessonRepository.findById(row.id()).orElseThrow();
        LessonAttachment att = attachmentRepository.saveAndFlush(new LessonAttachment(
                lesson.getId(), "main.pdf", "stored/main.pdf",
                "application/pdf", 512L, lecturer.getId()));
        lesson.setPdfAttachmentId(att.getId());
        lessonRepository.saveAndFlush(lesson);

        LessonForm form = new LessonForm(
                "Mixed", "DRAFT", "<p>Body</p>", "PDF", null, null);
        lessonsService.update(clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("PDF");
        assertThat(reloaded.getContentRichtext()).isNull();
        assertThat(reloaded.getPdfAttachmentId()).isEqualTo(att.getId());
    }

    @Test
    void update_switch_pdf_to_video_deletes_pdf_attachment_row() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "ToVideo", "DRAFT", "<p>Body</p>",
                lecturer.getId(), Role.LECTURER);
        Lesson lesson = lessonRepository.findById(row.id()).orElseThrow();
        LessonAttachment att = attachmentRepository.saveAndFlush(new LessonAttachment(
                lesson.getId(), "main.pdf", "stored/main.pdf",
                "application/pdf", 512L, lecturer.getId()));
        lesson.setPdfAttachmentId(att.getId());
        lesson.switchContentTypeTo("PDF");
        lessonRepository.saveAndFlush(lesson);

        lessonsService.setExternalVideo(clazz.getId(), section.getId(), row.id(),
                "YOUTUBE", "https://www.youtube.com/watch?v=abc123",
                lecturer.getId(), Role.LECTURER);
        LessonForm form = new LessonForm(
                "ToVideo", "DRAFT", "", "VIDEO",
                "https://www.youtube.com/watch?v=abc123", "YOUTUBE");
        lessonsService.update(clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("VIDEO");
        assertThat(reloaded.getPdfAttachmentId()).isNull();
        // The attachment row should be gone — the switcher deleted it.
        assertThat(attachmentRepository.findById(att.getId())).isEmpty();
    }

    @Test
    void update_switch_video_upload_to_richtext_clears_video_fields() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "BackToRT", "DRAFT", "",
                lecturer.getId(), Role.LECTURER);
        lessonsService.setUploadedVideo(clazz.getId(), section.getId(), row.id(),
                "lessons/" + row.id() + "/video/abc.mp4",
                lecturer.getId(), Role.LECTURER);
        // First switch the lesson INTO VIDEO so we can then switch BACK.
        LessonForm toVideo = new LessonForm(
                "BackToRT", "DRAFT", "", "VIDEO",
                "lessons/" + row.id() + "/video/abc.mp4", "UPLOAD");
        lessonsService.update(clazz.getId(), section.getId(), row.id(), toVideo,
                lecturer.getId(), Role.LECTURER);

        LessonForm backToRichtext = new LessonForm(
                "BackToRT", "DRAFT", "<p>Hello</p>", "RICHTEXT", null, null);
        lessonsService.update(clazz.getId(), section.getId(), row.id(), backToRichtext,
                lecturer.getId(), Role.LECTURER);

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("RICHTEXT");
        assertThat(reloaded.getVideoUrl()).isNull();
        assertThat(reloaded.getVideoProvider()).isNull();
        assertThat(reloaded.getContentRichtext()).contains("<p>Hello</p>");
    }

    @Test
    void update_switch_to_video_without_data_rejects_and_preserves_old() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "RTKeeper", "DRAFT", "<p>Body</p>",
                lecturer.getId(), Role.LECTURER);
        LessonForm form = new LessonForm(
                "RTKeeper", "DRAFT", "<p>Body</p>", "VIDEO", null, null);

        assertThatThrownBy(() -> lessonsService.update(
                clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chưa cấu hình video");

        Lesson reloaded = lessonRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getContentType()).isEqualTo("RICHTEXT");
        assertThat(reloaded.getContentRichtext()).contains("<p>Body</p>");
    }

    @Test
    void update_records_content_type_change_in_audit_metadata() {
        LessonRow row = lessonsService.create(
                clazz.getId(), section.getId(), "WillSwitch", "DRAFT", "<p>x</p>",
                lecturer.getId(), Role.LECTURER);
        Lesson lesson = lessonRepository.findById(row.id()).orElseThrow();
        LessonAttachment att = attachmentRepository.saveAndFlush(new LessonAttachment(
                lesson.getId(), "main.pdf", "stored/main.pdf",
                "application/pdf", 512L, lecturer.getId()));
        lesson.setPdfAttachmentId(att.getId());
        lessonRepository.saveAndFlush(lesson);

        LessonForm form = new LessonForm(
                "WillSwitch", "DRAFT", "<p>x</p>", "PDF", null, null);
        lessonsService.update(clazz.getId(), section.getId(), row.id(), form,
                lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findByLessonIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(LessonActivity.TYPE_UPDATED);
        assertThat(page.getContent().get(0).getMetadata())
                .contains("\"content_type\"")
                .contains("\"old\":\"RICHTEXT\"")
                .contains("\"new\":\"PDF\"");
    }

    // ── Helpers ───────────────────────────────────────────────────────

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

    private User ensureExtraLecturer() {
        String email = "lecturer-other@ulp.edu.vn";
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    "Lecturer Other",
                    Role.LECTURER,
                    true,
                    null,
                    null);
            return userRepository.saveAndFlush(u);
        });
    }
}
