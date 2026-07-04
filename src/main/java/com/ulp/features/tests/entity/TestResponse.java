package com.ulp.features.tests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code test_responses} table (V1). One row is a
 * student's answer to one {@link Question} inside an attempt. Selected option
 * ids are stored as a JSON array string (e.g. {@code "[12,15]"}); grading fills
 * {@code is_correct} + {@code points_earned}.
 */
@Entity
@Table(name = "test_responses")
public class TestResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "selected_option_ids", columnDefinition = "JSON")
    private String selectedOptionIds;

    @Column(name = "is_correct")
    private Boolean correct;

    @Column(name = "points_earned", precision = 5, scale = 2)
    private BigDecimal pointsEarned;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA-only constructor; do not call from application code. */
    protected TestResponse() {
    }

    public TestResponse(Long attemptId, Long questionId, String selectedOptionIds) {
        this.attemptId = attemptId;
        this.questionId = questionId;
        this.selectedOptionIds = selectedOptionIds;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** Applies the grading outcome for this response. */
    public void grade(boolean correct, BigDecimal pointsEarned) {
        this.correct = correct;
        this.pointsEarned = pointsEarned;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getAttemptId() { return attemptId; }
    public Long getQuestionId() { return questionId; }
    public String getSelectedOptionIds() { return selectedOptionIds; }
    public Boolean getCorrect() { return correct; }
    public BigDecimal getPointsEarned() { return pointsEarned; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
