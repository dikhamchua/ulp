package com.ulp.features.comments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transport DTOs for the lesson-comments API (ULP-4.6).
 *
 * <p>All comment content is plain text; the client renders it via
 * {@code textContent} so no server-side HTML escaping is needed here.
 */
public final class LessonCommentsDtos {

    private LessonCommentsDtos() {
        // utility holder
    }

    /**
     * One comment (root or reply) as returned to the client.
     *
     * @param id         comment primary key
     * @param parentId   root comment id for a reply; null for a root
     * @param authorName author display name (null on a placeholder)
     * @param lecturer   true when the author is the class's owning lecturer
     * @param content    plain-text body; null on a deleted-root placeholder
     * @param edited     true when the author edited the comment
     * @param deleted    true for a soft-deleted root kept as a placeholder
     * @param createdAt  creation timestamp (ISO-8601 when serialized)
     * @param canEdit    true when the caller may edit this comment
     * @param canDelete  true when the caller may delete this comment
     * @param replies    nested replies oldest-first (empty for a reply row)
     */
    public record CommentRow(
            Long id,
            Long parentId,
            String authorName,
            boolean lecturer,
            String content,
            boolean edited,
            boolean deleted,
            LocalDateTime createdAt,
            boolean canEdit,
            boolean canDelete,
            List<CommentRow> replies
    ) { }

    /** List response kept as an object so fields can be added without a break. */
    public record CommentListResponse(List<CommentRow> comments) { }

    /**
     * Create payload. Content is trim-then-validated in the service to reject
     * whitespace-only bodies; the annotations document the contract.
     */
    public record CreateRequest(
            @NotBlank(message = "Nội dung không được để trống")
            @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự")
            String content,
            Long parentId
    ) { }

    /** Edit payload — content only; same validation rules as create. */
    public record EditRequest(
            @NotBlank(message = "Nội dung không được để trống")
            @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự")
            String content
    ) { }
}
