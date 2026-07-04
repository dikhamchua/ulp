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
     * One comment node (any depth) as returned to the client.
     *
     * @param id              comment primary key
     * @param parentId        parent comment id; null for a root
     * @param authorName      author display name (null on a placeholder)
     * @param lecturer        true when the author is the class's owning lecturer
     * @param content         plain-text body; null on a deleted placeholder
     * @param edited          true when the author edited the comment
     * @param deleted         true for a soft-deleted node kept as a placeholder
     * @param createdAt       creation timestamp (ISO-8601 when serialized)
     * @param canEdit         true when the caller may edit this comment
     * @param canDelete       true when the caller may delete this comment
     * @param authorAvatarUrl author avatar image URL; null when none / placeholder
     * @param avatarLabel     initials fallback for a missing avatar; null on placeholder
     * @param avatarGradient  CSS gradient backing the initials; null on placeholder
     * @param hidden          true when the comment is moderator-hidden (REJECTED);
     *                        only ever true in a moderator listing
     * @param canModerate     true when the caller may hide/unhide this comment
     * @param replies         nested replies oldest-first (empty when none)
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
            String authorAvatarUrl,
            String avatarLabel,
            String avatarGradient,
            boolean hidden,
            boolean canModerate,
            List<CommentRow> replies
    ) { }

    /**
     * One "load more" page of ROOT comments plus their full reply trees.
     *
     * @param comments   assembled root rows for this page, newest-first
     * @param page       zero-based page index served
     * @param size       page size used (roots per page)
     * @param totalRoots total root comments visible to the caller across all pages
     * @param hasNext    true when a further page of roots exists
     */
    public record CommentPageView(
            List<CommentRow> comments,
            int page,
            int size,
            long totalRoots,
            boolean hasNext
    ) { }

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
