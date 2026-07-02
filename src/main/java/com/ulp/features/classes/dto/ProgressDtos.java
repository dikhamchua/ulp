package com.ulp.features.classes.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ulp.entities.ClassEntity;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * View-model DTOs for the lecturer-facing class progress dashboard
 * (lecturer-student-progress). All rows are read-only projections produced by
 * {@code LecturerProgressService}.
 *
 * <p>The drill-down records carry {@link JsonProperty} aliases so the JSON wire
 * shape is exactly {@code {sections:[{title,lessons:[{title,status}]}]}} while
 * the Java component names stay descriptive.
 */
public final class ProgressDtos {

    private ProgressDtos() {
        // utility holder
    }

    /**
     * One student row in the paginated progress table.
     *
     * @param userId         student's user id (drives the drill-down link)
     * @param fullName       display name
     * @param email          email address
     * @param avatarLabel    initials for the avatar chip
     * @param avatarGradient CSS gradient for the avatar chip
     * @param completed      count of COMPLETED published lessons
     * @param total          class-wide published lesson count (denominator)
     * @param percent        integer completion percent (0 when total is 0)
     * @param joinedAt       enrollment timestamp
     * @param lastActivity   most recent progress {@code updated_at}, or null
     * @param status         bucket key: not-started / in-progress / completed
     */
    public record StudentProgressRow(
            Long userId,
            String fullName,
            String email,
            String avatarLabel,
            String avatarGradient,
            int completed,
            int total,
            int percent,
            LocalDateTime joinedAt,
            LocalDateTime lastActivity,
            String status
    ) { }

    /**
     * Cohort summary metrics computed over the FULL active roster, independent
     * of any active search / filter / page.
     *
     * @param totalStudents   active-member count
     * @param avgPercent      mean of each student's percent (0 when no students)
     * @param notStartedCount students with no activity (never opened a lesson)
     * @param completedCount  students who completed every published lesson
     */
    public record ProgressSummary(
            int totalStudents,
            int avgPercent,
            int notStartedCount,
            int completedCount
    ) { }

    /** One lesson row in the drill-down, annotated with the student's status. */
    public record LessonProgressRow(
            @JsonProperty("title") String lessonTitle,
            String status
    ) { }

    /** A section group in the drill-down, holding its published lessons in order. */
    public record SectionProgressGroup(
            @JsonProperty("title") String sectionTitle,
            List<LessonProgressRow> lessons
    ) { }

    /** Top-level drill-down response — serialises to {@code {sections:[...]}}. */
    public record StudentBreakdown(
            List<SectionProgressGroup> sections
    ) { }

    /**
     * Controller view model for the progress tab: the authorised class (for the
     * sidebar), the cohort summary, and the current table window. Not sent as
     * JSON — consumed by the Thymeleaf view only.
     */
    public record ProgressPageView(
            ClassEntity clazz,
            ProgressSummary summary,
            Page<StudentProgressRow> rows
    ) { }
}
