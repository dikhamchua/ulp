package com.ulp.features.flashcards.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code flashcard_reviews} table (SM-2 spaced
 * repetition, ULP-5.x).
 *
 * <p>Holds exactly ONE row per (user, card) — enforced by the
 * {@code UNIQUE(user_id, flashcard_id)} constraint added in migration V18.
 * Each review carries the SM-2 state (easiness factor, repetitions, interval)
 * and the computed next-due timestamp; a new rating upserts this row.
 *
 * <p>{@code easiness_factor} is a {@code DECIMAL(5,2)} in the schema, so it is
 * mapped as {@link BigDecimal}; callers convert to/from {@code double} at the
 * scheduler boundary.
 */
@Entity
@Table(name = "flashcard_reviews")
public class FlashcardReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "flashcard_id", nullable = false)
    private Long flashcardId;

    // Schema column is TINYINT; map the JDBC type so Hibernate validate matches.
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private int quality;

    @Column(name = "easiness_factor", nullable = false, precision = 5, scale = 2)
    private BigDecimal easinessFactor;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(nullable = false)
    private int repetitions;

    @Column(name = "next_review_at", nullable = false)
    private LocalDateTime nextReviewAt;

    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    /** JPA-only constructor; do not call from application code. */
    protected FlashcardReview() {
    }

    /**
     * Creates the first review row for a (user, card) pair.
     *
     * @param userId         reviewing user id
     * @param flashcardId    reviewed card id
     * @param quality        SM-2 recall quality (0..5)
     * @param easinessFactor new easiness factor
     * @param intervalDays   new interval in days
     * @param repetitions    new repetition count
     * @param nextReviewAt   computed next-due timestamp
     */
    public FlashcardReview(Long userId, Long flashcardId, int quality,
                           double easinessFactor, int intervalDays,
                           int repetitions, LocalDateTime nextReviewAt) {
        this.userId = userId;
        this.flashcardId = flashcardId;
        apply(quality, easinessFactor, intervalDays, repetitions, nextReviewAt);
    }

    @PrePersist
    void onPersist() {
        if (reviewedAt == null) reviewedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        reviewedAt = LocalDateTime.now();
    }

    /** Applies a fresh SM-2 result to this row (upsert path). */
    public void apply(int quality, double easinessFactor, int intervalDays,
                      int repetitions, LocalDateTime nextReviewAt) {
        this.quality = quality;
        this.easinessFactor = BigDecimal.valueOf(easinessFactor);
        this.intervalDays = intervalDays;
        this.repetitions = repetitions;
        this.nextReviewAt = nextReviewAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getFlashcardId() {
        return flashcardId;
    }

    public int getQuality() {
        return quality;
    }

    /** Easiness factor as a primitive for the SM-2 scheduler. */
    public double getEasinessFactor() {
        return easinessFactor.doubleValue();
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public LocalDateTime getNextReviewAt() {
        return nextReviewAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }
}
