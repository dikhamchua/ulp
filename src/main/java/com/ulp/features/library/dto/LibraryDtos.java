package com.ulp.features.library.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTOs for the lecturer personal file library (SSR page + JSON picker).
 */
public final class LibraryDtos {

    private LibraryDtos() {
    }

    /** One asset row on the SSR library page. */
    public record LibraryAssetRow(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * Paginated SSR list view model. {@code page} is a Spring Data page so the
     * shared pager fragment can consume it directly. Kind counts power the
     * left sidebar badges (all / document / video) independent of the active
     * search filter so the folder rail stays stable while browsing.
     */
    public record LibraryPageView(
            Page<LibraryAssetRow> page,
            String q,
            String kind,
            long totalCount,
            long documentCount,
            long videoCount
    ) {
    }

    /** JSON item returned by the picker API. */
    public record LibraryPickerItem(
            Long id,
            String title,
            String originalFilename,
            String kind,
            String mimeType,
            long sizeBytes
    ) {
    }

    /** JSON page envelope for the picker modal. */
    public record LibraryPickerPage(
            List<LibraryPickerItem> items,
            int page,
            int size,
            int totalPages,
            long totalElements
    ) {
    }

    /** One editable class row for the library attach wizard. */
    public record AttachTargetClassRow(
            Long id,
            String name,
            String code
    ) {
    }

    /** Paginated classes for wizard step 1. */
    public record AttachTargetClassesPage(
            List<AttachTargetClassRow> items,
            int page,
            int size,
            int totalPages,
            long totalElements
    ) {
    }

    /** Section row for wizard step 2. */
    public record AttachTargetSectionRow(
            Long id,
            String title,
            short displayOrder
    ) {
    }

    /** Lesson row for wizard step 3. */
    public record AttachTargetLessonRow(
            Long id,
            String title,
            String status,
            String contentType,
            short displayOrder
    ) {
    }

    /**
     * Whether the target lesson already has a main PDF / uploaded video body
     * so the wizard can prompt for replace confirmation.
     */
    public record AttachLessonContentSummary(
            Long lessonId,
            boolean hasMainPdf,
            boolean hasUploadedVideo,
            String pdfFilename,
            String videoProvider
    ) {
    }

    /** One lesson-template row on the library "Bài giảng" tab. */
    public record LessonTemplateRow(
            Long id,
            String title,
            String contentType,
            LocalDateTime updatedAt,
            int attachmentCount
    ) {
    }

    /**
     * One live lesson row on the library "Bài giảng" tab (across the lecturer's
     * classes). {@code editUrl} / {@code cloneUrl} are pre-built for the template.
     */
    public record LibraryLessonRow(
            Long lessonId,
            Long classId,
            Long sectionId,
            String title,
            String contentType,
            String status,
            String className,
            String sectionTitle,
            LocalDateTime updatedAt,
            String editUrl,
            String cloneUrl
    ) {
    }

    /** Paginated SSR view for the templates rail. */
    public record LessonTemplatePageView(
            Page<LessonTemplateRow> page,
            String q,
            long templateCount,
            long totalCount,
            long documentCount,
            long videoCount
    ) {
    }

    /**
     * SSR view for the library "Bài giảng" tab: live lessons across classes plus
     * saved-template count for the sidebar badge (templates stay secondary).
     *
     * <p>{@code classId} is the active class filter (null = all). {@code classOptions}
     * powers the header dropdown.
     */
    public record LibraryLessonsPageView(
            Page<LibraryLessonRow> page,
            String q,
            Long classId,
            List<AttachTargetClassRow> classOptions,
            long lessonCount,
            long templateCount,
            long totalCount,
            long documentCount,
            long videoCount
    ) {
    }

    /** Result of cloning a template or lesson into a destination section. */
    public record LessonCloneResult(
            Long lessonId,
            Long classId,
            Long sectionId,
            String title
    ) {
    }
}
