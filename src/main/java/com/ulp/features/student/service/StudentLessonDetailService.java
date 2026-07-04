package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.entities.Section;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.support.ClassAccessPolicy;
import com.ulp.features.lessons.support.LessonAccessResolver;
import com.ulp.features.lessons.support.VimeoEmbedUrl;
import com.ulp.features.lessons.support.YouTubeEmbedUrl;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonAttachmentRow;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.ulp.common.IConstant.CONTENT_TYPE_PDF;
import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;
import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_VIMEO;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_YOUTUBE;

/**
 * Read service backing the student-facing lesson detail page at
 * {@code GET /my/classes/{classId}/lessons/{lessonId}}.
 *
 * <p>Authz gates are applied in this order; any failure collapses
 * to the same {@link EntityNotFoundException} so existence is never
 * leaked:
 * <ol>
 *   <li>Class MUST be live ({@code @SQLRestriction} filters
 *       soft-deletes), the lesson's owning section MUST belong to
 *       {@code classId} (prevents cross-class URL fuzzing), and the
 *       lesson MUST be {@link Lesson#STATUS_PUBLISHED} and not
 *       soft-deleted — all resolved by {@link LessonAccessResolver}.</li>
 *   <li>The caller MUST be admitted: an ACTIVE-enrolled student, OR the
 *       owning lecturer, OR an ADMIN/HEAD moderator (bypasses enrollment
 *       so they can open the lesson's discussion thread to moderate —
 *       design D7, mirroring {@code LessonCommentsService.authorize}
 *       D3). The lesson gates run first, so a moderator gains nothing on
 *       a deleted / unpublished lesson.</li>
 * </ol>
 *
 * <p>The view model carries {@code contentType} + per-type body URLs
 * so the template can switch between viewers without touching the
 * entity directly.
 */
@Service
public class StudentLessonDetailService {

    private static final String ATTACHMENT_DOWNLOAD_URL_FMT =
            "/api/lessons/%d/attachments/%d/download";
    private static final String VIDEO_STREAM_URL_FMT =
            "/api/lessons/%d/video/stream";
    private static final String FILE_VIEWER_URL_FMT =
            "/file-viewer?type=%s&lessonId=%d&attachmentId=%d&filename=%s";
    /** Lazy MS Office viewer redirect — mints the public token only on click. */
    private static final String OFFICE_VIEWER_URL_FMT =
            "/file-viewer/office?lessonId=%d&attachmentId=%d";

    /** Extensions supported by the internal DOCX viewer (JSZip + docx-preview). */
    private static final Set<String> DOCX_EXTENSIONS = Set.of("docx", "doc");
    /** Extensions that still need MS Office Viewer (no client-side renderer). */
    private static final Set<String> OFFICE_EXTENSIONS =
            Set.of("pptx", "ppt", "xlsx", "xls");

    private final LessonAttachmentRepository lessonAttachmentRepository;
    private final LessonAccessResolver lessonAccessResolver;
    private final ClassAccessPolicy accessPolicy;

    public StudentLessonDetailService(LessonAttachmentRepository lessonAttachmentRepository,
                                      LessonAccessResolver lessonAccessResolver,
                                      ClassAccessPolicy accessPolicy) {
        this.lessonAttachmentRepository = lessonAttachmentRepository;
        this.lessonAccessResolver = lessonAccessResolver;
        this.accessPolicy = accessPolicy;
    }

    /**
     * Returns the populated view model for a single PUBLISHED lesson,
     * admitting an ACTIVE-enrolled student, the owning lecturer, or an
     * ADMIN/HEAD moderator.
     *
     * @param classId  target class id from the URL
     * @param lessonId target lesson id from the URL
     * @param userId   authenticated user id
     * @param role     the caller's role; ADMIN/HEAD bypass enrollment
     * @return populated {@link LessonDetailView}; the attachments list is
     *         empty when the lesson has none
     * @throws EntityNotFoundException whenever any gate fails — always
     *         with the message {@code "Class not found or not accessible"}
     */
    @Transactional(readOnly = true)
    public LessonDetailView getLessonDetail(Long classId, Long lessonId, Long userId, Role role) {
        // Lesson gates first (live class, section-belongs-to-class, PUBLISHED)
        // resolve the trio via the shared resolver; failures collapse to 404.
        // Running these before the access gate means a moderator gains nothing
        // on a deleted / unpublished lesson.
        LessonAccessResolver.ResolvedLesson resolved =
                lessonAccessResolver.resolveInClass(classId, lessonId);
        ClassEntity clazz = resolved.clazz();
        Section section = resolved.section();
        Lesson lesson = resolved.lesson();

        // Access gate: ADMIN/HEAD bypass enrollment so they can open the
        // lesson (and its discussion thread) to moderate; the owning lecturer
        // passes too; otherwise an ACTIVE enrollment is required. Any other
        // caller (REMOVED/COMPLETED/non-enrolled non-moderator) → no-leak 404.
        accessPolicy.requireModeratorOrEnrolled(clazz, userId, role);

        List<LessonAttachment> rawAttachments = lessonAttachmentRepository
                .findByLessonIdOrderByUploadedAtAsc(lessonId);
        // The main PDF lives in the same lesson_attachments table; the
        // accessory list should skip it so the embed viewer doesn't double
        // up. Lessons that are not PDF type render every attachment as
        // accessory (no "main PDF" exists for those types).
        List<LessonAttachmentRow> attachments = new ArrayList<>(rawAttachments.size());
        Long mainPdfId = lesson.getPdfAttachmentId();
        for (LessonAttachment a : rawAttachments) {
            if (mainPdfId != null && mainPdfId.equals(a.getId())) continue;
            attachments.add(new LessonAttachmentRow(
                    a.getId(),
                    a.getOriginalFilename(),
                    a.getSizeBytes(),
                    a.getMimeType(),
                    attachmentDownloadUrl(lessonId, a.getId()),
                    buildAttachmentViewUrl(lessonId, a)));
        }

        String contentType = lesson.getContentType() == null
                ? CONTENT_TYPE_RICHTEXT : lesson.getContentType();
        String pdfDownloadUrl = buildPdfDownloadUrl(lesson);
        String pdfViewerUrl = buildPdfViewerUrl(lesson);
        String videoUrl = buildStudentVideoUrl(lesson);

        return new LessonDetailView(
                clazz.getId(),
                clazz.getName(),
                lesson.getId(),
                lesson.getTitle(),
                section.getId(),
                section.getTitle(),
                lesson.getContentRichtext(),
                lesson.getPublishedAt(),
                attachments,
                contentType,
                pdfDownloadUrl,
                pdfViewerUrl,
                videoUrl,
                lesson.getVideoProvider());
    }

    /** Returns the canonical PDF stream URL when type=PDF; null otherwise. */
    private String buildPdfDownloadUrl(Lesson lesson) {
        if (!CONTENT_TYPE_PDF.equals(lesson.getContentType())
                || lesson.getPdfAttachmentId() == null) {
            return null;
        }
        return attachmentDownloadUrl(lesson.getId(), lesson.getPdfAttachmentId());
    }

    /**
     * Returns the PDF.js viewer page URL when type=PDF; null otherwise.
     */
    private String buildPdfViewerUrl(Lesson lesson) {
        if (!CONTENT_TYPE_PDF.equals(lesson.getContentType())
                || lesson.getPdfAttachmentId() == null) {
            return null;
        }
        String pdfFilename = lessonAttachmentRepository.findById(lesson.getPdfAttachmentId())
                .map(LessonAttachment::getOriginalFilename)
                .orElse("tai-lieu.pdf");
        return fileViewerUrl("pdf", lesson.getId(),
                lesson.getPdfAttachmentId(), pdfFilename);
    }

    /**
     * Builds an inline viewer URL for an accessory attachment.
     * PDF → PDF.js; DOCX → JSZip + docx-preview; PPTX/XLSX → MS Office.
     * The MS Office path routes through a lazy endpoint that mints the
     * public token only when the student actually opens the file, so
     * merely rendering the list has no write side-effect.
     */
    private String buildAttachmentViewUrl(Long lessonId, LessonAttachment a) {
        String ext = extractExtension(a.getOriginalFilename());
        String fname = a.getOriginalFilename();
        if ("pdf".equals(ext)) {
            return fileViewerUrl("pdf", lessonId, a.getId(), fname);
        }
        if (DOCX_EXTENSIONS.contains(ext)) {
            return fileViewerUrl("docx", lessonId, a.getId(), fname);
        }
        if (OFFICE_EXTENSIONS.contains(ext)) {
            return String.format(OFFICE_VIEWER_URL_FMT, lessonId, a.getId());
        }
        return null;
    }

    private static String fileViewerUrl(String type, Long lessonId,
                                         Long attachmentId, String filename) {
        return String.format(FILE_VIEWER_URL_FMT, type, lessonId, attachmentId,
                URLEncoder.encode(filename != null ? filename : "tai-lieu", StandardCharsets.UTF_8));
    }

    /** Returns the iframe-embed URL or MP4 stream URL when type=VIDEO; null otherwise. */
    private String buildStudentVideoUrl(Lesson lesson) {
        if (!CONTENT_TYPE_VIDEO.equals(lesson.getContentType())
                || lesson.getVideoProvider() == null || lesson.getVideoUrl() == null) {
            return null;
        }
        String provider = lesson.getVideoProvider();
        if (VIDEO_PROVIDER_YOUTUBE.equals(provider)) {
            return YouTubeEmbedUrl.toEmbedUrl(lesson.getVideoUrl());
        }
        if (VIDEO_PROVIDER_VIMEO.equals(provider)) {
            return VimeoEmbedUrl.toEmbedUrl(lesson.getVideoUrl());
        }
        if (VIDEO_PROVIDER_UPLOAD.equals(provider)) {
            return String.format(VIDEO_STREAM_URL_FMT, lesson.getId());
        }
        return null;
    }

    /** Builds the canonical download URL for an attachment row. */
    private static String attachmentDownloadUrl(Long lessonId, Long attachmentId) {
        return String.format(ATTACHMENT_DOWNLOAD_URL_FMT, lessonId, attachmentId);
    }

    /** Extracts the lowercase file extension from a filename. */
    private static String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

