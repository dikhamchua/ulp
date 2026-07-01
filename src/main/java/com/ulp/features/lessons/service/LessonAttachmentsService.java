package com.ulp.features.lessons.service;

import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.upload.LessonAttachmentStorageService;
import com.ulp.features.upload.LessonAttachmentStorageService.StoredAttachment;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_FORBIDDEN_FOR_CLASS;
import static com.ulp.common.IConstant.MSG_LESSON_NOT_FOUND;

/**
 * Business service for lesson attachments.
 *
 * <p>Three-layer auth on upload/delete/list: class-level edit via
 * {@link ClassesService#getEditable}, section↔class binding via
 * {@link LessonsReorderService#verifySectionBelongsToClass}, and
 * lesson↔section binding via {@link LessonRepository#findByIdAndSectionId}.
 * Download widens this: enrolled students may download only when the
 * parent lesson is {@code PUBLISHED}. Cascade on lesson soft-delete is
 * application-level — {@link LessonsService#delete} calls
 * {@link #deleteAllByLesson(Long)} BEFORE markDeleted.
 */
@Service
public class LessonAttachmentsService {

    private final LessonAttachmentRepository attachmentRepository;
    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final LessonAttachmentStorageService storage;
    private final ClassesService classesService;
    private final LessonsReorderService reorderService;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonActivityWriter activityWriter;

    public LessonAttachmentsService(LessonAttachmentRepository attachmentRepository,
                                    LessonRepository lessonRepository,
                                    SectionRepository sectionRepository,
                                    LessonAttachmentStorageService storage,
                                    ClassesService classesService,
                                    LessonsReorderService reorderService,
                                    EnrollmentRepository enrollmentRepository,
                                    LessonActivityWriter activityWriter) {
        this.attachmentRepository = attachmentRepository;
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.storage = storage;
        this.classesService = classesService;
        this.reorderService = reorderService;
        this.enrollmentRepository = enrollmentRepository;
        this.activityWriter = activityWriter;
    }

    /** Lists attachments of a lesson — used to preload the edit page. */
    @Transactional(readOnly = true)
    public List<LessonAttachmentRow> listForLesson(Long classId, Long sectionId, Long lessonId,
                                                   Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        loadLesson(sectionId, lessonId);
        return mapRows(attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId));
    }

    /**
     * Stores the uploaded file on disk and persists the metadata row.
     *
     * @throws IllegalArgumentException with a Vietnamese-friendly message
     *                                  for any validation failure
     * @throws IOException              if the file cannot be written to disk
     */
    @Transactional
    public LessonAttachmentRow upload(Long classId, Long sectionId, Long lessonId,
                                      MultipartFile file, Long userId, Role role) throws IOException {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        loadLesson(sectionId, lessonId);

        StoredAttachment stored = storage.store(file, lessonId);
        LessonAttachment row = new LessonAttachment(lessonId, stored.originalFilename(),
                stored.storedPath(), stored.mimeType(), stored.sizeBytes(), userId);
        LessonAttachment saved = attachmentRepository.save(row);
        activityWriter.write(lessonId, LessonActivity.TYPE_ATTACHMENT_ADDED,
                "Thêm tệp đính kèm: " + saved.getOriginalFilename(), userId);
        return toRow(saved);
    }

    /**
     * Uploads a PDF and binds it as the lesson's main PDF content body.
     * When the lesson already has a main PDF the previous attachment row
     * + on-disk file are deleted first so disk usage stays bounded and
     * a single main PDF invariant holds.
     *
     * @throws IllegalArgumentException when the file is not a PDF or the
     *                                  upload validation fails
     */
    @Transactional
    public LessonAttachmentRow uploadMainPdf(Long classId, Long sectionId, Long lessonId,
                                             MultipartFile file, Long userId, Role role)
            throws IOException {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);

        if (file == null || !"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new IllegalArgumentException("Chỉ chấp nhận tệp PDF cho bài giảng dạng PDF");
        }

        Long previousMainId = lesson.getPdfAttachmentId();

        // Save new PDF first so the CHECK constraint (content_type=PDF
        // requires pdf_attachment_id NOT NULL) is never violated.
        StoredAttachment stored = storage.store(file, lessonId);
        LessonAttachment row = new LessonAttachment(lessonId, stored.originalFilename(),
                stored.storedPath(), stored.mimeType(), stored.sizeBytes(), userId);
        LessonAttachment saved = attachmentRepository.saveAndFlush(row);
        lesson.setPdfAttachmentId(saved.getId());
        lessonRepository.saveAndFlush(lesson);

        // Clean up old main PDF now that the new one is in place.
        if (previousMainId != null && !previousMainId.equals(saved.getId())) {
            attachmentRepository.findById(previousMainId).ifPresent(prev -> {
                storage.delete(prev.getStoredPath());
                attachmentRepository.delete(prev);
            });
        }
        activityWriter.write(lessonId, LessonActivity.TYPE_PDF_UPLOADED,
                "Tải lên PDF chính: " + saved.getOriginalFilename(), userId);
        return toRow(saved);
    }

    /** Hard-deletes a single attachment (DB row + on-disk file). */
    @Transactional
    public void delete(Long classId, Long sectionId, Long lessonId, Long attachmentId,
                       Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        reorderService.verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        LessonAttachment att = attachmentRepository.findByIdAndLessonId(attachmentId, lessonId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_ATTACHMENT_NOT_FOUND));
        // If this is the main PDF, switch the lesson to RICHTEXT first
        // so clearing pdf_attachment_id doesn't violate the CHECK constraint.
        if (attachmentId.equals(lesson.getPdfAttachmentId())) {
            lesson.updateContent("");
            lesson.switchContentTypeTo(Lesson.CONTENT_TYPE_RICHTEXT);
            lessonRepository.saveAndFlush(lesson);
        } else {
            lessonRepository.clearPdfAttachmentId(attachmentId);
        }
        String removedName = att.getOriginalFilename();
        storage.delete(att.getStoredPath());
        attachmentRepository.delete(att);
        activityWriter.write(lessonId, LessonActivity.TYPE_ATTACHMENT_REMOVED,
                "Xoá tệp đính kèm: " + removedName, userId);
    }

    /**
     * Cascade cleanup invoked from {@link LessonsService#delete} BEFORE the
     * lesson is soft-deleted. Removes every attachment row + on-disk file.
     */
    @Transactional
    public void deleteAllByLesson(Long lessonId) {
        List<LessonAttachment> rows = attachmentRepository.findByLessonIdOrderByUploadedAtAsc(lessonId);
        for (LessonAttachment att : rows) {
            // Clear FK before delete — lessons.pdf_attachment_id is RESTRICT with no ON DELETE clause.
            lessonRepository.clearPdfAttachmentId(att.getId());
            storage.delete(att.getStoredPath());
        }
        if (!rows.isEmpty()) attachmentRepository.deleteByLessonId(lessonId);
    }

    /**
     * Authorizes a download request and returns the resolved file handle.
     * Lecturers/heads/admins of the owning class always pass; an enrolled
     * student passes only when the parent lesson is {@code PUBLISHED}.
     */
    @Transactional(readOnly = true)
    public DownloadHandle download(Long lessonId, Long attachmentId, Long userId, Role role) {
        LessonAttachment att = attachmentRepository.findByIdAndLessonId(attachmentId, lessonId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_ATTACHMENT_NOT_FOUND));
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));

        Long classId = resolveClassId(lesson);
        boolean allowed = isLecturerOrAbove(role)
                ? canEditClass(classId, userId, role)
                : isEnrolledStudentForPublishedLesson(classId, userId, lesson);
        if (!allowed) throw new AccessDeniedException(MSG_FORBIDDEN_FOR_CLASS);

        Path absolute = storage.resolveAbsolutePath(att.getStoredPath());
        return new DownloadHandle(absolute, att.getOriginalFilename(),
                att.getMimeType(), att.getSizeBytes());
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private Lesson loadLesson(Long sectionId, Long lessonId) {
        return lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
    }

    /** Traverses lesson → section → class id. */
    private Long resolveClassId(Lesson lesson) {
        Section section = sectionRepository.findById(lesson.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
        return section.getClassId();
    }

    private boolean canEditClass(Long classId, Long userId, Role role) {
        try {
            classesService.getEditable(classId, userId, role);
            return true;
        } catch (AccessDeniedException | EntityNotFoundException ex) {
            return false;
        }
    }

    private boolean isEnrolledStudentForPublishedLesson(Long classId, Long userId, Lesson lesson) {
        if (!LESSON_STATUS_PUBLISHED.equals(lesson.getStatus())) return false;
        Optional<Enrollment> enrollment = enrollmentRepository.findByUserIdAndClassId(userId, classId);
        return enrollment.filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus())).isPresent();
    }

    private static boolean isLecturerOrAbove(Role role) {
        return role == Role.LECTURER || role == Role.HEAD || role == Role.ADMIN;
    }

    private static List<LessonAttachmentRow> mapRows(List<LessonAttachment> rows) {
        List<LessonAttachmentRow> out = new ArrayList<>(rows.size());
        for (LessonAttachment a : rows) out.add(toRow(a));
        return out;
    }

    private static LessonAttachmentRow toRow(LessonAttachment a) {
        return new LessonAttachmentRow(a.getId(), a.getOriginalFilename(),
                a.getMimeType(), a.getSizeBytes(), a.getUploadedAt(),
                "/api/lessons/" + a.getLessonId() + "/attachments/" + a.getId() + "/download");
    }

    /** Tuple returned by {@link #download} so the controller can stream the file. */
    public record DownloadHandle(Path absolutePath, String originalFilename,
                                 String mimeType, long sizeBytes) {
    }
}
