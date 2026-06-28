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
     * @param id          lesson primary key (used in the placeholder href
     *                    for ULP-4.2 lesson detail)
     * @param title       display title
     * @param sectionId   id of the owning section — kept on the row so the
     *                    template can build per-lesson links without a
     *                    second lookup
     * @param publishedAt when the lesson was published (display only)
     */
    public record StudentLessonRow(
            Long id,
            String title,
            Long sectionId,
            LocalDateTime publishedAt
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
     * stays free of URL string concatenation (see ULP-4.2 design D7). The
     * URL targets the existing endpoint exposed by capability
     * {@code lesson-attachments} (ULP-4.0c) — no new download route.
     *
     * @param id          attachment primary key
     * @param filename    original filename as uploaded by the lecturer
     * @param sizeBytes   file size in bytes
     * @param mimeType    resolved MIME type (from the extension whitelist)
     * @param downloadUrl absolute path to the existing download endpoint
     */
    public record LessonAttachmentRow(
            Long id,
            String filename,
            long sizeBytes,
            String mimeType,
            String downloadUrl
    ) { }

    /**
     * Top-level view model for the student-facing lesson detail page at
     * {@code /my/classes/{classId}/lessons/{lessonId}}.
     *
     * <p>Built entirely inside the service's read transaction so the
     * template never touches a lazy collection (see ULP-4.2 design D9).
     * All fields are nullable-safe for the template: empty content is
     * allowed (template renders a placeholder) and empty attachments
     * collapse to an absent section.
     *
     * @param classId         owning class id (used by the back link)
     * @param className       owning class display name (breadcrumb)
     * @param lessonId        lesson primary key
     * @param lessonTitle     lesson display title
     * @param sectionId       owning section id (used by the back link
     *                        to pre-select the section on the list page)
     * @param sectionTitle    owning section display title (breadcrumb)
     * @param contentRichtext sanitised rich-text HTML body; may be null
     *                        or empty when the lecturer published an
     *                        outline-only lesson
     * @param publishedAt     timestamp the lesson was published
     * @param attachments     attachment rows in upload order; may be empty
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
            List<LessonAttachmentRow> attachments
    ) { }
}
