package com.ulp.features.tests.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity mapping the {@code question_options} table (V1). An option belongs
 * to exactly one {@link Question} via {@code question_id}. {@code is_correct}
 * marks the option(s) that make up the correct answer — never sent to the
 * student while taking the exam.
 */
@Entity
@Table(name = "question_options")
public class QuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(name = "is_correct", nullable = false)
    private boolean correct = false;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    /** JPA-only constructor; do not call from application code. */
    protected QuestionOption() {
    }

    public QuestionOption(Long questionId, String content, boolean correct, Integer sortOrder) {
        this.questionId = questionId;
        this.content = content;
        this.correct = correct;
        this.sortOrder = sortOrder;
    }

    /** Updates author-editable fields without changing the identity / FK. */
    public void updateContent(String content, boolean correct, Integer sortOrder) {
        this.content = content;
        this.correct = correct;
        this.sortOrder = sortOrder;
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public Long getQuestionId() { return questionId; }
    public String getContent() { return content; }
    public boolean isCorrect() { return correct; }
    public Integer getSortOrder() { return sortOrder; }
}
