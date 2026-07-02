package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code comments} table (ULP-4.6).
 *
 * <p>A comment is a plain-text question or reply on a lesson. One level of
 * threading is supported: {@code parent_id} is null for a root question and
 * points at a root comment for a reply (reply-to-reply is flattened to the
 * root at the service layer).
 *
 * <p><b>No {@code @SQLRestriction}:</b> unlike other soft-deleted entities,
 * this one deliberately omits the {@code is_deleted = 0} filter. The list
 * query MUST still see a soft-deleted root so it can render a "comment
 * removed" placeholder when live replies hang under it — soft-delete
 * filtering therefore happens in the repository queries / service instead.
 */
@Entity
@Table(name = "comments")
public class Comment {

    public static final String MODERATION_PENDING = "PENDING";
    public static final String MODERATION_APPROVED = "APPROVED";
    public static final String MODERATION_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Null for a root question; a root comment id for a reply. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_edited", nullable = false)
    private boolean edited = false;

    @Column(name = "is_pinned", nullable = false)
    private boolean pinned = false;

    @Column(name = "moderation_status", nullable = false, length = 20)
    private String moderationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /** JPA-only constructor; do not call from application code. */
    protected Comment() {
    }

    /**
     * Creates a new APPROVED comment ready to persist. Callers pass an
     * already-trimmed, length-validated content string.
     *
     * @param lessonId owning lesson id
     * @param userId   author id
     * @param parentId root comment id when this is a reply; null for a root
     * @param content  trimmed plain-text body (1..2000 chars)
     */
    public Comment(Long lessonId, Long userId, Long parentId, String content) {
        this.lessonId = lessonId;
        this.userId = userId;
        this.parentId = parentId;
        this.content = content;
        this.moderationStatus = MODERATION_APPROVED;
        this.edited = false;
        this.pinned = false;
        this.deleted = false;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Business helpers ───────────────────────────────────────────────

    public boolean isRoot() {
        return parentId == null;
    }

    /**
     * Replaces the body and flags the comment as edited. Caller passes an
     * already-trimmed, length-validated content string.
     */
    public void edit(String newContent) {
        this.content = newContent;
        this.edited = true;
    }

    /** Marks the comment soft-deleted; replies are NOT cascade-deleted. */
    public void markDeleted() {
        this.deleted = true;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getContent() {
        return content;
    }

    public boolean isEdited() {
        return edited;
    }

    public boolean isPinned() {
        return pinned;
    }

    public String getModerationStatus() {
        return moderationStatus;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
