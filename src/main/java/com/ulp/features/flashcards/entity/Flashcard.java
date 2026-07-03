package com.ulp.features.flashcards.entity;

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
 * JPA entity mapping the {@code flashcards} table (ULP-5.x).
 *
 * <p>A card is a two-sided item inside a {@link FlashcardDeck}. Each side has
 * required text; cards keep their position in the deck via {@code sort_order}.
 *
 * <p>No {@code @Data} — explicit getters + a single mutation helper.
 */
@Entity
@Table(name = "flashcards")
public class Flashcard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deck_id", nullable = false)
    private Long deckId;

    @Column(name = "front_text", nullable = false, columnDefinition = "TEXT")
    private String frontText;

    @Column(name = "back_text", nullable = false, columnDefinition = "TEXT")
    private String backText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Flashcard() {
    }

    /**
     * Creates a new card ready to persist.
     *
     * @param deckId    owning deck id
     * @param frontText trimmed, non-blank front text
     * @param backText  trimmed, non-blank back text
     * @param sortOrder zero-based position in the deck
     */
    public Flashcard(Long deckId, String frontText, String backText, int sortOrder) {
        this.deckId = deckId;
        this.frontText = frontText;
        this.backText = backText;
        this.sortOrder = sortOrder;
    }

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

    /** Replaces text and position for an in-place edit of a kept card. */
    public void update(String frontText, String backText, int sortOrder) {
        this.frontText = frontText;
        this.backText = backText;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getDeckId() {
        return deckId;
    }

    public String getFrontText() {
        return frontText;
    }

    public String getBackText() {
        return backText;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
