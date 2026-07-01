package com.ulp.features.lessons.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonForm;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.upload.LessonVideoStorageService;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;
import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.MSG_LESSON_NOT_FOUND;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_VIMEO;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_YOUTUBE;

/**
 * Lesson CRUD service for the lessons tab.
 *
 * <p>Covers list, create, update, and soft-delete. Publish/unpublish is
 * on {@link LessonsPublishService}; reorder + ordering validation on
 * {@link LessonsReorderService}; diff-metadata builder + status
 * transition via {@link LessonsUpdateHelper}. Type-switch cleanup lives
 * in {@link LessonContentTypeSwitcher}.
 *
 * <p>Every mutating method enforces ownership via
 * {@link ClassesService#getEditable}: a LECTURER may only manage lessons
 * inside classes they own; HEAD and ADMIN may manage any class. The
 * section↔class binding is verified through
 * {@link LessonsReorderService#verifySectionBelongsToClass}.
 *
 * <p>Rich-text bodies are sanitised through {@link HtmlSanitizer} before
 * persistence; PDF + VIDEO bodies travel through dedicated content
 * endpoints.
 */
@Service
public class LessonsService {

    private final LessonRepository lessonRepository;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;
    private final LessonsReorderService reorderService;
    private final LessonsUpdateHelper updateHelper;
    private final LessonAttachmentsService attachmentsService;
    private final LessonContentTypeSwitcher contentTypeSwitcher;
    private final LessonVideoStorageService videoStorageService;

    public LessonsService(LessonRepository lessonRepository,
                          ClassesService classesService,
                          LessonActivityWriter activityWriter,
                          LessonsReorderService reorderService,
                          LessonsUpdateHelper updateHelper,
                          LessonAttachmentsService attachmentsService,
                          LessonContentTypeSwitcher contentTypeSwitcher,
                          LessonVideoStorageService videoStorageService) {
        this.lessonRepository = lessonRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
        this.reorderService = reorderService;
        this.updateHelper = updateHelper;
        this.attachmentsService = attachmentsService;
        this.contentTypeSwitcher = contentTypeSwitcher;
        this.videoStorageService = videoStorageService;
    }

    /** Lists the lessons of a section in their authored order. Lecturers see
     *  their own classes only; enrolled students use a separate read path. */
    @Transactional(readOnly = true)
    public List<LessonRow> listForSection(Long classId, Long sectionId,
                                          Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        List<Lesson> lessons = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(sectionId);
        List<LessonRow> rows = new ArrayList<>(lessons.size());
        for (Lesson l : lessons) {
            rows.add(toRow(l));
        }
        return rows;
    }

    /**
     * Creates a new lesson appended after the current last one. Defaults
     * the content type to RICHTEXT for backward compatibility — the
     * lecturer flips the type via the form picker and the dedicated
     * content endpoints. Sanitises the body only on the RICHTEXT path.
     */
    @Transactional
    public LessonRow create(Long classId, Long sectionId, String title,
                            String status, String contentHtmlRaw,
                            Long userId, Role role) {
        ClassEntity clazz = classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);

        short nextOrder = (short) (lessonRepository.findMaxDisplayOrder(sectionId) + 1);
        Lesson lesson = new Lesson(sectionId, title, nextOrder, userId);
        // Create always lands as RICHTEXT — the lecturer creates a draft,
        // then optionally uploads a PDF/MP4 and saves the type switch.
        lesson.updateContent(HtmlSanitizer.sanitize(contentHtmlRaw));
        if (LESSON_STATUS_PUBLISHED.equals(status)) {
            lesson.publish();
        }
        Lesson saved = lessonRepository.save(lesson);

        activityWriter.write(
                saved.getId(),
                LessonActivity.TYPE_CREATED,
                "Tạo bài giảng " + saved.getTitle(),
                userId);

        // Surface the publish event as a separate audit row so the timeline
        // shows "Đã xuất bản" alongside "Đã tạo" when publishing on create.
        if (LESSON_STATUS_PUBLISHED.equals(saved.getStatus())) {
            activityWriter.write(
                    saved.getId(),
                    LessonActivity.TYPE_PUBLISHED,
                    "Xuất bản bài giảng " + saved.getTitle(),
                    userId);
        }
        // Reference clazz id so the compiler does not warn about an unused
        // local — the lookup is for auth side-effect only.
        if (clazz.getId() == null) {
            throw new IllegalStateException("Class id missing after auth check");
        }
        return toRow(saved);
    }

    /**
     * Updates the title / status / type / body of an existing lesson.
     *
     * <p>The UPDATED activity row is written only when title, sanitised
     * body, or content type actually changed — re-submitting the
     * unchanged form does not pollute the history. Status transitions
     * ride on their own activity types (PUBLISHED / UNPUBLISHED) so the
     * timeline can highlight publish events distinctly.
     */
    @Transactional
    public LessonRow update(Long classId, Long sectionId, Long lessonId,
                            LessonForm form, Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);

        String oldTitle = lesson.getTitle();
        String oldType = lesson.getContentType();
        String oldBody = nullToEmpty(lesson.getContentRichtext());
        String requestedType = form.effectiveContentType();
        boolean typeChanged = !Objects.equals(oldType, requestedType);

        // The "Lưu URL" AJAX button is gone — the main form save now owns
        // external video URLs. Persist them BEFORE the type-switch so the
        // switcher's "video configured?" check sees the fresh values.
        // UPLOAD keeps the stored MP4 path the upload endpoint wrote, so
        // only YouTube/Vimeo URLs flow in from the form.
        if (CONTENT_TYPE_VIDEO.equals(requestedType)
                && (VIDEO_PROVIDER_YOUTUBE.equals(form.videoProvider())
                    || VIDEO_PROVIDER_VIMEO.equals(form.videoProvider()))) {
            lesson.setVideoProvider(form.videoProvider());
            lesson.setVideoUrl(form.videoUrl());
        }

        if (typeChanged) {
            // Cross-type updates delegate to the switcher which validates,
            // cleans up, and writes the new type's body data.
            contentTypeSwitcher.applyTo(lesson, form);
        } else if (CONTENT_TYPE_RICHTEXT.equals(requestedType)) {
            // Same-type RICHTEXT update keeps the existing sanitise path.
            lesson.updateContent(HtmlSanitizer.sanitize(form.contentHtml()));
        }
        // Same-type PDF / VIDEO: the dedicated content endpoints already
        // wrote the body fields. The form save only touches title/status.

        lesson.rename(form.title());
        Lesson saved = lessonRepository.save(lesson);

        String newBody = nullToEmpty(saved.getContentRichtext());
        boolean titleChanged = !Objects.equals(oldTitle, form.title());
        boolean bodyChanged = !Objects.equals(oldBody, newBody);
        if (titleChanged || bodyChanged || typeChanged) {
            updateHelper.writeUpdateActivity(saved, oldTitle, oldBody, newBody,
                    titleChanged, bodyChanged, typeChanged, oldType, requestedType, userId);
        }

        // Handle the status transition AFTER the UPDATED row so the history
        // reads "Đã cập nhật → Đã xuất bản" in chronological order.
        updateHelper.applyStatusTransition(saved, form.status(), userId);
        return toRow(saved);
    }

    /**
     * Soft-deletes a lesson; releases its display_order slot via
     * {@link Lesson#markDeleted()}. Hard-deletes attachments and any
     * uploaded MP4 first so disk usage does not accumulate.
     */
    @Transactional
    public void delete(Long classId, Long sectionId, Long lessonId,
                       Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        // Cascade hard-delete of attachments (rows + on-disk files) BEFORE the
        // lesson is soft-deleted — see design D2. A failure here aborts the
        // soft-delete so the lesson is never half-deleted with orphan files.
        attachmentsService.deleteAllByLesson(lessonId);
        // VIDEO/UPLOAD: drop the MP4 file too. Safe to call when the lesson
        // has no video — the storage service no-ops on a missing directory.
        if (CONTENT_TYPE_VIDEO.equals(lesson.getContentType())
                && VIDEO_PROVIDER_UPLOAD.equals(lesson.getVideoProvider())) {
            videoStorageService.deleteByLessonId(lessonId);
        }
        lesson.markDeleted();
        lessonRepository.save(lesson);
        activityWriter.write(
                lesson.getId(),
                LessonActivity.TYPE_DELETED,
                "Xoá bài giảng " + lesson.getTitle(),
                userId);
    }

    /** Delegates to {@link LessonsReorderService#reorder} — kept here so the
     *  public service API stays stable for existing callers and tests. */
    @Transactional
    public void reorder(Long classId, Long sectionId, List<Long> orderedIds,
                        Long userId, Role role) {
        reorderService.reorder(classId, sectionId, orderedIds, userId, role);
    }

    // ── Content endpoints — out-of-band PDF / video updates ────────────

    /**
     * Sets the lesson's {@code video_url} + {@code video_provider} from an
     * external URL (YouTube/Vimeo). Validates ownership + section binding
     * the same way edit-form save does. Does NOT switch the content type
     * — the lecturer must save the type via the form afterwards.
     */
    @Transactional
    public Lesson setExternalVideo(Long classId, Long sectionId, Long lessonId,
                                   String provider, String url,
                                   Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        lesson.setVideoProvider(provider);
        lesson.setVideoUrl(url);
        return lessonRepository.save(lesson);
    }

    /**
     * Sets the lesson's {@code video_url} to the stored MP4 path and
     * {@code video_provider} to UPLOAD. Caller passes the path returned by
     * {@code LessonVideoStorageService.store}.
     */
    @Transactional
    public Lesson setUploadedVideo(Long classId, Long sectionId, Long lessonId,
                                   String relativePath, Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        lesson.setVideoProvider(VIDEO_PROVIDER_UPLOAD);
        lesson.setVideoUrl(relativePath);
        return lessonRepository.save(lesson);
    }

    /** Looks up a lesson with the standard class/section auth chain. */
    @Transactional(readOnly = true)
    public Lesson getEditableLesson(Long classId, Long sectionId, Long lessonId,
                                    Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        return loadLesson(sectionId, lessonId);
    }

    // ── Backward-compatible update overload ────────────────────────────

    /**
     * Update overload for callers that don't use the content-type form
     * fields. Wraps raw parameters into a {@link LessonForm} that keeps
     * the lesson's current type so behavior matches the full-form path.
     */
    @Transactional
    public LessonRow update(Long classId, Long sectionId, Long lessonId,
                            String title, String status, String contentHtmlRaw,
                            Long userId, Role role) {
        Lesson current = loadLesson(sectionId, lessonId);
        LessonForm form = new LessonForm(title, status, contentHtmlRaw,
                current.getContentType(), current.getVideoUrl(),
                current.getVideoProvider());
        return update(classId, sectionId, lessonId, form, userId, role);
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private Lesson loadLesson(Long sectionId, Long lessonId) {
        return lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
    }

    private static LessonRow toRow(Lesson l) {
        return new LessonRow(l.getId(), l.getTitle(), l.getStatus(),
                l.getDisplayOrder() == null ? 0 : l.getDisplayOrder(),
                l.getContentType());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
