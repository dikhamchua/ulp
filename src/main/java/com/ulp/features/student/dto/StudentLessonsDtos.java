package com.ulp.features.student.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * View-model DTOs for the student-facing
 * {@code /my/classes/{classId}/lessons} page.
 *
 * <p>All rows are read-only projections produced by
 * {@code StudentLessonsService}. DRAFT lessons are filtered out at the
 * service layer before any DTO is built, so anything in this file can
 * be assumed PUBLISHED.
 */
public class StudentLessonsDtos {

    /**
     * A single PUBLISHED lesson row rendered in the main panel.
     *
     * @param id          lesson primary key (drives the detail link)
     * @param title       display title
     * @param sectionId   id of the owning section — kept on the row so the
     *                    template can build per-lesson links without a
     *                    second lookup
     * @param publishedAt when the lesson was published (display only)
     * @param contentType RICHTEXT / PDF / VIDEO — lets the right-rail card
     *                    pick the matching thumb icon without a service hop
     */
    public record StudentLessonRow(
            Long id,
            String title,
            Long sectionId,
            LocalDateTime publishedAt,
            String contentType
    ) { }

    /**
     * One sidebar entry plus the list of PUBLISHED lessons it owns.
     *
     * <p>The lessons list MAY be empty — the page intentionally keeps
     * empty sections visible in the sidebar (see design D4).
     */
    public record SectionWithLessons(
            Long sectionId,
            String title,
            short displayOrder,
            List<StudentLessonRow> lessons
    ) { }

    /**
     * Top-level view model for the page. Includes the class id (used to
     * build per-lesson hrefs) and class name (rendered in the header).
     */
    public record ClassLessonsView(
            Long classId,
            String className,
            List<SectionWithLessons> sections
    ) { }

    /**
     * One attachment row rendered on the student-facing lesson detail page.
     *
     * <p>The {@code downloadUrl} is built once by the service so the view
     * stays free of URL string concatenation. The URL targets the existing
     * attachment download endpoint — no new download route.
     *
     * @param id          attachment primary key
     * @param filename    original filename as uploaded by the lecturer
     * @param sizeBytes   file size in bytes
     * @param mimeType    resolved MIME type (from the extension whitelist)
     * @param downloadUrl absolute path to the existing download endpoint
     * @param viewUrl     inline viewer URL, or null if the format is not
     *                    supported (PDF → PDF.js; DOCX/PPTX/XLSX → MS Office)
     */
    public record LessonAttachmentRow(
            Long id,
            String filename,
            long sizeBytes,
            String mimeType,
            String downloadUrl,
            String viewUrl
    ) {
        /**
         * Human-readable size string (B / KB / MB). Mirrors the
         * lecturer-side formatter in {@code lesson-attachments.js} so
         * both surfaces show consistent units.
         */
        public String formattedSize() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024L * 1024L) {
                return String.format("%.1f KB", sizeBytes / 1024.0);
            }
            return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }

        /**
         * Uppercase file extension derived from the last dot in
         * {@link #filename()}; empty string when the filename has no
         * extension. Used by the student lesson-detail template; computed
         * here because Thymeleaf 3 lacks a {@code substringAfterLast}
         * utility on {@code #strings}.
         */
        public String extension() {
            if (filename == null) return "";
            int dot = filename.lastIndexOf('.');
            if (dot < 0 || dot == filename.length() - 1) return "";
            return filename.substring(dot + 1).toUpperCase();
        }
    }

    /**
     * Top-level view model for the student-facing lesson detail page at
     * {@code /my/classes/{classId}/lessons/{lessonId}}.
     *
     * <p>Built entirely inside the service's read transaction so the
     * template never touches a lazy collection. All fields are
     * nullable-safe: empty content renders a placeholder and empty
     * attachments collapse to an absent section.
     *
     * <p>The {@code contentType} discriminator selects the viewer;
     * {@code pdfDownloadUrl} serves the embedded PDF path,
     * {@code videoUrl} carries the embed URL (YOUTUBE/VIMEO) or stream
     * endpoint (UPLOAD), and {@code videoProvider} identifies the source.
     *
     * @param classId         owning class id (used by the back link)
     * @param className       owning class display name (breadcrumb)
     * @param lessonId        lesson primary key
     * @param lessonTitle     lesson display title
     * @param sectionId       owning section id (pre-selects the section
     *                        on the list page)
     * @param sectionTitle    owning section display title (breadcrumb)
     * @param contentRichtext sanitised rich-text HTML body; may be null
     * @param publishedAt     timestamp the lesson was published
     * @param attachments     attachment rows in upload order; may be empty
     * @param contentType     RICHTEXT / PDF / VIDEO — selects the viewer
     * @param pdfDownloadUrl  download URL of the main PDF when type=PDF
     * @param pdfViewerUrl    PDF.js iframe URL when type=PDF; null otherwise
     * @param videoUrl        embed URL (YOUTUBE/VIMEO) or stream URL (UPLOAD)
     * @param videoProvider   YOUTUBE / VIMEO / UPLOAD — null outside VIDEO
     */
    public record LessonDetailView(
            Long classId,
            String className,
            Long lessonId,
            String lessonTitle,
            Long sectionId,
            String sectionTitle,
            String contentRichtext,
            LocalDateTime publishedAt,
            List<LessonAttachmentRow> attachments,
            String contentType,
            String pdfDownloadUrl,
            String pdfViewerUrl,
            String videoUrl,
            String videoProvider
    ) { }
}
