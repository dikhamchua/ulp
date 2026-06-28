package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonAttachmentRow;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read service backing the student-facing lesson detail page at
 * {@code GET /my/classes/{classId}/lessons/{lessonId}} (ULP-4.2).
 *
 * <p>Four authz gates are applied in this order; any failure collapses
 * to the same {@link EntityNotFoundException} so existence is never
 * leaked (see ULP-4.2 design D1–D5):
 * <ol>
 *   <li>Caller MUST have an enrollment row for {@code classId} with
 *       status {@code ACTIVE}.</li>
 *   <li>Class MUST be live ({@code @SQLRestriction} filters
 *       soft-deletes).</li>
 *   <li>The lesson's owning section MUST belong to {@code classId}
 *       (prevents cross-class URL fuzzing).</li>
 *   <li>The lesson MUST be {@link Lesson#STATUS_PUBLISHED} and not
 *       soft-deleted.</li>
 * </ol>
 */
@Service
public class StudentLessonDetailService {

    /** Path template for the attachment download endpoint (ULP-4.0c). */
    private static final String ATTACHMENT_DOWNLOAD_URL_FMT =
            "/api/lessons/%d/attachments/%d/download";

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final LessonAttachmentRepository lessonAttachmentRepository;

    public StudentLessonDetailService(EnrollmentRepository enrollmentRepository,
                                      ClassRepository classRepository,
                                      SectionRepository sectionRepository,
                                      LessonRepository lessonRepository,
                                      LessonAttachmentRepository lessonAttachmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.lessonAttachmentRepository = lessonAttachmentRepository;
    }

    /**
     * Returns the populated view model for a single PUBLISHED lesson
     * visible to an ACTIVE-enrolled student.
     *
     * @param classId  target class id from the URL
     * @param lessonId target lesson id from the URL
     * @param userId   authenticated user id
     * @return populated {@link LessonDetailView}; the attachments list is
     *         empty when the lesson has none
     * @throws EntityNotFoundException whenever any gate fails — always
     *         with the message {@code "Class not found or not accessible"}
     */
    @Transactional(readOnly = true)
    public LessonDetailView getLessonDetail(Long classId, Long lessonId, Long userId) {
        // Gate 1: enrollment must be ACTIVE — REMOVED/COMPLETED → 404.
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));

        // Gate 2: class must be live. @SQLRestriction filters soft-deletes.
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));

        // Gate 3: lesson + section lookup. SQLRestriction filters
        // soft-deleted lessons; missing → 404.
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));
        Section section = sectionRepository.findById(lesson.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));
        // Cross-class guard: deny if the lesson lives in another class.
        if (!classId.equals(section.getClassId())) {
            throw new EntityNotFoundException("Class not found or not accessible");
        }

        // Gate 4: DRAFT lessons are lecturer-private — never visible here.
        if (!Lesson.STATUS_PUBLISHED.equals(lesson.getStatus())) {
            throw new EntityNotFoundException("Class not found or not accessible");
        }

        List<LessonAttachment> rawAttachments = lessonAttachmentRepository
                .findByLessonIdOrderByUploadedAtAsc(lessonId);
        List<LessonAttachmentRow> attachments = new ArrayList<>(rawAttachments.size());
        for (LessonAttachment a : rawAttachments) {
            attachments.add(new LessonAttachmentRow(
                    a.getId(),
                    a.getOriginalFilename(),
                    a.getSizeBytes(),
                    a.getMimeType(),
                    attachmentDownloadUrl(lessonId, a.getId())));
        }

        return new LessonDetailView(
                clazz.getId(),
                clazz.getName(),
                lesson.getId(),
                lesson.getTitle(),
                section.getId(),
                section.getTitle(),
                lesson.getContentRichtext(),
                lesson.getPublishedAt(),
                attachments);
    }

    /** Builds the canonical download URL for an attachment row. */
    private static String attachmentDownloadUrl(Long lessonId, Long attachmentId) {
        return String.format(ATTACHMENT_DOWNLOAD_URL_FMT, lessonId, attachmentId);
    }
}
