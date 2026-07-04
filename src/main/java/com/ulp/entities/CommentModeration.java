package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the existing {@code comment_moderation} audit table (V1).
 *
 * <p>One row is written per moderator action on a comment: {@code REJECTED}
 * when a comment is hidden, {@code APPROVED} when it is unhidden. The
 * {@code reason} column stays null in this change (no UI to capture it). The
 * table pre-exists in the V1 schema, so no migration is added.
 */
@Entity
@Table(name = "comment_moderation")
public class CommentModeration {

    public static final String ACTION_APPROVED = "APPROVED";
    public static final String ACTION_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "moderated_by", nullable = false)
    private Long moderatedBy;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA-only constructor; do not call from application code. */
    protected CommentModeration() {
    }

    private CommentModeration(Long commentId, Long moderatedBy, String action) {
        this.commentId = commentId;
        this.moderatedBy = moderatedBy;
        this.action = action;
    }

    /**
     * Builds an audit row for a moderator action ready to persist.
     *
     * @param commentId   the moderated comment id
     * @param moderatedBy the acting moderator's user id
     * @param action      {@link #ACTION_APPROVED} or {@link #ACTION_REJECTED}
     */
    public static CommentModeration record(Long commentId, Long moderatedBy, String action) {
        return new CommentModeration(commentId, moderatedBy, action);
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getCommentId() {
        return commentId;
    }

    public Long getModeratedBy() {
        return moderatedBy;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
