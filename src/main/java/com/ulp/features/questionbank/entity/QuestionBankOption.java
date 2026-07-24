package com.ulp.features.questionbank.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Answer option belonging to a department question bank item.
 */
@Entity
@Table(name = "question_bank_options")
public class QuestionBankOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    protected QuestionBankOption() {
    }

    public QuestionBankOption(Long itemId, String content, boolean correct, Integer sortOrder) {
        this.itemId = itemId;
        this.content = content;
        this.correct = correct;
        this.sortOrder = sortOrder;
    }

    /** Updates the editable option payload while preserving row identity. */
    public void updateContent(String content, boolean correct, Integer sortOrder) {
        this.content = content;
        this.correct = correct;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public Long getItemId() {
        return itemId;
    }

    public String getContent() {
        return content;
    }

    public boolean isCorrect() {
        return correct;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
