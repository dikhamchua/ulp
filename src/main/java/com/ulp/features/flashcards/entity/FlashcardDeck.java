package com.ulp.features.flashcards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code flashcard_decks} table (ULP-5.x).
 *
 * <p>A deck is a personal collection of two-sided cards owned by a student or
 * a lecturer (a lecturer may share to the classes they teach).
 *
 * <p>Sharing state is split across two places. Which classes a deck targets
 * lives entirely in {@link FlashcardDeckClass}, and a deck is class-shared
 * exactly when it has at least one row there. The public-link state, by
 * contrast, lives on this row: {@code is_public} is the on/off switch and
 * {@code share_token} is the link identity.
 *
 * <p>{@link SQLRestriction} filters soft-deleted rows out of every default
 * query, mirroring {@link com.ulp.entities.ClassEntity}. No {@code @Data} —
 * explicit getters + business helpers only (like {@link com.ulp.entities.Comment}).
 */
@Entity
@Table(name = "flashcard_decks")
@SQLRestriction("is_deleted = 0")
public class FlashcardDeck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "share_token", length = 40)
    private String shareToken;

    @Column(name = "is_public", nullable = false)
    private boolean publicLink = false;

    /** JPA-only constructor; do not call from application code. */
    protected FlashcardDeck() {
    }

    /**
     * Creates a new deck ready to persist. A fresh deck targets no classes, so
     * it is private until the owner adds sharing targets.
     *
     * @param ownerId     creator/owner id
     * @param title       trimmed, non-blank title
     * @param description optional description (may be null/blank)
     */
    public FlashcardDeck(Long ownerId, String title, String description) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
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

    /** Updates editable metadata; caller passes a trimmed, non-blank title. */
    public void updateMetadata(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** Marks the deck soft-deleted; excluded from all default queries. */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * Turns the public link on, minting a token only when the deck has none.
     * Keeping an existing token means a link shared earlier still resolves.
     *
     * @param freshToken a newly generated token, used only on first enable
     */
    public void enablePublicLink(String freshToken) {
        if (this.shareToken == null) {
            this.shareToken = freshToken;
        }
        this.publicLink = true;
    }

    /** Turns the link off but keeps the token so re-enabling revives sent URLs. */
    public void disablePublicLink() {
        this.publicLink = false;
    }

    /**
     * Replaces the token, permanently killing every URL already handed out.
     * Leaves the on/off switch untouched — the caller decides that separately.
     *
     * @param freshToken the replacement token
     */
    public void regenerateToken(String freshToken) {
        this.shareToken = freshToken;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /** The public-link token, or null when the link has never been enabled. */
    public String getShareToken() {
        return shareToken;
    }

    /** Whether the public link is currently switched on. */
    public boolean isPublicLink() {
        return publicLink;
    }
}
