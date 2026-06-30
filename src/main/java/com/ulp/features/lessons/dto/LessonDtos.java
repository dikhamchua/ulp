package com.ulp.features.lessons.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;

/**
 * DTOs for the lesson CRUD feature on the lessons tab.
 *
 * <p>{@link LessonForm} carries the content-type discriminator + video
 * fields alongside the classic rich-text path. {@link LessonRow} exposes
 * the {@code contentType} badge for the lesson list.
 */
public final class LessonDtos {

    private LessonDtos() {
        // utility holder
    }

    /** A single lesson as rendered in the lessons-tab list. */
    public record LessonRow(Long id, String title, String status, short displayOrder,
                            String contentType) {
    }

    /**
     * Form payload used by the create / edit endpoints. {@code contentHtml}
     * is the raw Quill output — the service runs it through
     * {@link com.ulp.common.HtmlSanitizer} before persistence.
     *
     * <p>{@code contentType} selects which body the lesson actually carries
     * once persisted: RICHTEXT uses {@code contentHtml}; PDF expects a
     * pre-uploaded {@code pdf_attachment_id} on the lesson (set by the
     * dedicated content endpoint); VIDEO expects {@code videoProvider}
     * plus {@code videoUrl} which the service stores verbatim.
     */
    public record LessonForm(
            @NotBlank(message = "Tiêu đề bài giảng không được để trống")
            @Size(max = 300, message = "Tiêu đề tối đa 300 ký tự")
            String title,

            @Pattern(regexp = "DRAFT|PUBLISHED",
                    message = "Trạng thái không hợp lệ")
            String status,

            String contentHtml,

            @Pattern(regexp = "RICHTEXT|PDF|VIDEO",
                    message = "Loại nội dung không hợp lệ")
            String contentType,

            String videoUrl,

            @Pattern(regexp = "YOUTUBE|VIMEO|UPLOAD|",
                    message = "Nhà cung cấp video không hợp lệ")
            String videoProvider
    ) {
        /** Convenience: defaults the type to RICHTEXT when the form omits it. */
        public String effectiveContentType() {
            return (contentType == null || contentType.isBlank())
                    ? CONTENT_TYPE_RICHTEXT : contentType;
        }
    }

    /** Payload for the reorder endpoint — full ordered id list. */
    public record LessonReorderRequest(List<Long> orderedIds) {
    }

    /**
     * One audit-log row as rendered in the lesson history tab. Maps a
     * {@link com.ulp.entities.LessonActivity} entity onto the fields the
     * Thymeleaf template consumes.
     */
    public record LessonActivityRow(
            Long id,
            String type,
            String typeLabel,
            String description,
            LocalDateTime createdAt
    ) {
    }

    /**
     * One attachment row as rendered in the lesson edit page and returned by
     * the upload / list endpoints.
     *
     * @param id               attachment row id
     * @param originalFilename filename as the lecturer uploaded it
     * @param mimeType         resolved MIME type
     * @param sizeBytes        file size in bytes (JS formats to KB/MB)
     * @param uploadedAt       insert timestamp
     * @param downloadUrl      canonical download URL
     */
    public record LessonAttachmentRow(
            Long id,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            LocalDateTime uploadedAt,
            String downloadUrl
    ) {
    }

    /**
     * JSON envelope returned by the three content endpoints (PDF / video /
     * video-url). Carries the new persisted state so the lecturer-side JS
     * can re-render the form without a full page reload.
     */
    public record LessonContentSummary(
            String contentType,
            Long pdfAttachmentId,
            String pdfFilename,
            String videoUrl,
            String videoProvider
    ) {
    }
}
