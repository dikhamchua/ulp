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
 * <p>A deck is a personal collection of two-sided cards owned by a student.
 * A fresh deck is {@link #VISIBILITY_PRIVATE}; the owner may switch it to
 * {@link #VISIBILITY_SHARED} targeting one of their classes so enrolled
 * classmates can view/study it. {@code OFFICIAL} decks are out of scope this
 * change but the value is kept for schema fidelity.
 *
 * <p>{@link SQLRestriction} filters soft-deleted rows out of every default
 * query, mirroring {@link com.ulp.entities.ClassEntity}. No {@code @Data} —
 * explicit getters + business helpers only (like {@link com.ulp.entities.Comment}).
 */
@Entity
@Table(name = "flashcard_decks")
@SQLRestriction("is_deleted = 0")
public class FlashcardDeck {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_SHARED = "SHARED";
    public static final String VISIBILITY_OFFICIAL = "OFFICIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Null when PRIVATE; the target class id when SHARED. */
    @Column(name = "class_id")
    private Long classId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 20)
    private String visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /** JPA-only constructor; do not call from application code. */
    protected FlashcardDeck() {
    }

    /**
     * Creates a new PRIVATE deck ready to persist.
     *
     * @param ownerId     creator/owner id
     * @param title       trimmed, non-blank title
     * @param description optional description (may be null/blank)
     */
    public FlashcardDeck(Long ownerId, String title, String description) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
        this.visibility = VISIBILITY_PRIVATE;
        this.classId = null;
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

    /** Moves the deck to SHARED targeting the given class. */
    public void shareTo(Long classId) {
        this.visibility = VISIBILITY_SHARED;
        this.classId = classId;
    }

    /** Reverts the deck to PRIVATE and clears its target class. */
    public void unshare() {
        this.visibility = VISIBILITY_PRIVATE;
        this.classId = null;
    }

    /** Marks the deck soft-deleted; excluded from all default queries. */
    public void markDeleted() {
        this.deleted = true;
    }

    public boolean isShared() {
        return VISIBILITY_SHARED.equals(visibility);
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

    public Long getClassId() {
        return classId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getVisibility() {
        return visibility;
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
}
