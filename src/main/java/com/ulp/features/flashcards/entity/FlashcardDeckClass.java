package com.ulp.features.flashcards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code flashcard_deck_classes} join table: one row per
 * (deck, class) sharing target.
 *
 * <p>This table is the single source of truth for deck sharing — a deck is
 * shared exactly when it has at least one row here. No {@code @Data}: explicit
 * getters only, matching {@link FlashcardDeck}.
 */
@Entity
@Table(name = "flashcard_deck_classes")
public class FlashcardDeckClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deck_id", nullable = false)
    private Long deckId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "shared_at", nullable = false, updatable = false)
    private LocalDateTime sharedAt;

    /** JPA-only constructor; do not call from application code. */
    protected FlashcardDeckClass() {
    }

    /**
     * Creates a new sharing target ready to persist.
     *
     * @param deckId  the deck being shared
     * @param classId the class gaining access
     */
    public FlashcardDeckClass(Long deckId, Long classId) {
        this.deckId = deckId;
        this.classId = classId;
    }

    @PrePersist
    void onPersist() {
        if (sharedAt == null) sharedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDeckId() {
        return deckId;
    }

    public Long getClassId() {
        return classId;
    }

    public LocalDateTime getSharedAt() {
        return sharedAt;
    }
}
