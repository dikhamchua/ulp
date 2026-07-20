package com.ulp.features.lessons.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.entities.Lesson;
import com.ulp.features.lessons.dto.LessonDtos.LessonForm;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.upload.LessonAttachmentStorageService;
import com.ulp.features.upload.LessonVideoStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.ulp.common.IConstant.CONTENT_TYPE_PDF;
import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;
import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.MSG_LESSON_CONTENT_TYPE_REQUIRED;
import static com.ulp.common.IConstant.MSG_LESSON_PDF_NOT_UPLOADED;
import static com.ulp.common.IConstant.MSG_LESSON_VIDEO_NOT_CONFIGURED;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;

/**
 * Orchestrates a content-type switch on a {@link Lesson}, including the
 * file-cleanup side effects for the previous type.
 *
 * <p>The method runs inside the caller's transaction (REQUIRED) so a
 * downstream failure rolls back BOTH the type switch and any persisted
 * state. File deletion happens after the validation check passes so the
 * on-disk artifact lingers only when we are committed to the switch — see
 * design D7.
 */
@Service
public class LessonContentTypeSwitcher {

    private final LessonAttachmentRepository attachmentRepository;
    private final LessonRepository lessonRepository;
    private final LessonAttachmentStorageService attachmentStorage;
    private final LessonVideoStorageService videoStorage;

    public LessonContentTypeSwitcher(LessonAttachmentRepository attachmentRepository,
                                     LessonRepository lessonRepository,
                                     LessonAttachmentStorageService attachmentStorage,
                                     LessonVideoStorageService videoStorage) {
        this.attachmentRepository = attachmentRepository;
        this.lessonRepository = lessonRepository;
        this.attachmentStorage = attachmentStorage;
        this.videoStorage = videoStorage;
    }

    /**
     * Switches {@code lesson}'s content type to the type encoded in
     * {@code form}, applying validation + cleanup.
     *
     * @throws IllegalArgumentException with a locked Vietnamese-facing
     *         message when the new type's required data is missing
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void applyTo(Lesson lesson, LessonForm form) {
        String newType = form.effectiveContentType();
        Lesson.validateContentType(newType);
        validateRequiredDataPresent(lesson, form, newType);

        String oldType = lesson.getContentType();
        // Capture the FK reference (if any) BEFORE we let the entity null it
        // out — the switch must happen first so the CHECK constraint sees
        // the right type/columns combination, then we drop the old type's
        // server-side artifacts.
        Long previousPdfId = lesson.getPdfAttachmentId();
        boolean hadUploadVideo = (CONTENT_TYPE_VIDEO.equals(oldType)
                && VIDEO_PROVIDER_UPLOAD.equals(lesson.getVideoProvider()));
        // Library videos must never be wiped from disk on type switch.
        boolean hadLibraryVideo = hadUploadVideo && lesson.hasLibraryVideo();

        applyNewTypeData(lesson, form, newType);
        // switchContentTypeTo nulls fields not belonging to the new type so
        // the entity now matches its CHECK shape — flush the row before
        // deleting referenced rows so the FK clear UPDATE does not violate
        // the constraint on the still-old shape.
        lesson.switchContentTypeTo(newType);
        lessonRepository.saveAndFlush(lesson);

        cleanupForOldType(oldType, newType, previousPdfId, hadUploadVideo,
                hadLibraryVideo, lesson.getId());
    }

    /**
     * Verifies the lesson holds the data required by the new type.
     * For PDF / VIDEO the data was set by the dedicated content endpoints
     * before the lecturer saved the form; for RICHTEXT we accept any
     * body (including blank — the service stores an empty string).
     */
    private void validateRequiredDataPresent(Lesson lesson, LessonForm form, String newType) {
        switch (newType) {
            case CONTENT_TYPE_RICHTEXT -> {
                // Empty body is allowed — the service writes an empty
                // string so the CHECK constraint passes.
            }
            case CONTENT_TYPE_PDF -> {
                if (lesson.getPdfAttachmentId() == null) {
                    throw new IllegalArgumentException(MSG_LESSON_PDF_NOT_UPLOADED);
                }
            }
            case CONTENT_TYPE_VIDEO -> {
                if (lesson.getVideoUrl() == null || lesson.getVideoUrl().isBlank()
                        || lesson.getVideoProvider() == null || lesson.getVideoProvider().isBlank()) {
                    throw new IllegalArgumentException(MSG_LESSON_VIDEO_NOT_CONFIGURED);
                }
            }
            default -> throw new IllegalArgumentException(MSG_LESSON_CONTENT_TYPE_REQUIRED);
        }
    }

    /** Drops files / FK references that no longer belong to the lesson. */
    private void cleanupForOldType(String oldType, String newType, Long previousPdfId,
                                   boolean hadUploadVideo, boolean hadLibraryVideo,
                                   Long lessonId) {
        if (oldType == null || oldType.equals(newType)) return;
        if (CONTENT_TYPE_PDF.equals(oldType) && previousPdfId != null) {
            // Lesson row has already been re-typed; the FK column is now
            // NULL, but the orphan attachment row still exists. Clear any
            // lingering reference defensively then delete the row (+ file
            // only when the old main PDF was a one-off upload).
            lessonRepository.clearPdfAttachmentId(previousPdfId);
            attachmentRepository.findById(previousPdfId).ifPresent(att -> {
                if (!att.isLibraryBacked()) {
                    attachmentStorage.delete(att.getStoredPath());
                }
                attachmentRepository.delete(att);
            });
        }
        // Only wipe the lesson video directory for one-off uploads.
        if (hadUploadVideo && !hadLibraryVideo) {
            videoStorage.deleteByLessonId(lessonId);
        }
    }

    /** Writes the new type's body data onto the entity. */
    private void applyNewTypeData(Lesson lesson, LessonForm form, String newType) {
        if (CONTENT_TYPE_RICHTEXT.equals(newType)) {
            String sanitised = HtmlSanitizer.sanitize(form.contentHtml());
            // Ensure the CHECK constraint is satisfied — null would fail.
            lesson.updateContent(sanitised == null ? "" : sanitised);
        }
        // PDF / VIDEO data was already written by the content endpoints —
        // switchContentTypeTo only retains it.
    }
}
