package com.ulp.features.tests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code questions} table (V1). A question belongs to
 * exactly one {@link Test} via {@code test_id}. Only the MCQ and MR types are
 * used by this feature (FILL_IN / MATCHING are out of scope).
 */
@Entity
@Table(name = "questions")
public class Question {

    public static final String TYPE_MCQ = "MCQ";
    public static final String TYPE_MR = "MR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_id", nullable = false)
    private Long testId;

    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(precision = 5, scale = 2)
    private BigDecimal points = BigDecimal.ONE;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Question() {
    }

    public Question(Long testId, String questionType, String content, String explanation,
                    BigDecimal points, Integer sortOrder) {
        this.testId = testId;
        this.questionType = questionType;
        this.content = content;
        this.explanation = explanation;
        this.points = points;
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

    public boolean isMultiResponse() {
        return TYPE_MR.equals(questionType);
    }

    /** Updates author-editable fields without changing the identity / FK. */
    public void updateContent(String questionType, String content, String explanation,
                              BigDecimal points, Integer sortOrder) {
        this.questionType = questionType;
        this.content = content;
        this.explanation = explanation;
        this.points = points;
        this.sortOrder = sortOrder;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getTestId() { return testId; }
    public String getQuestionType() { return questionType; }
    public String getContent() { return content; }
    public String getExplanation() { return explanation; }
    public BigDecimal getPoints() { return points; }
    public Integer getSortOrder() { return sortOrder; }
}
