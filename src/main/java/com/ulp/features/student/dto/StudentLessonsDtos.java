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
}
