package com.ulp.features.questionbank.entity;

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
 * Department-scoped shared question contribution curated by the department HEAD,
 * independent of any test.
 */
@Entity
@Table(name = "question_bank_items")
public class QuestionBankItem {

    public static final String TYPE_MCQ = "MCQ";
    public static final String TYPE_MR = "MR";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_REVIEW = "REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "contributor_id", nullable = false)
    private Long contributorId;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    @Column(name = "workflow_status", nullable = false, length = 20)
    private String workflowStatus = STATUS_DRAFT;

    @Column(name = "status_before_archive", length = 20)
    private String statusBeforeArchive;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected QuestionBankItem() {
    }

    public QuestionBankItem(Long departmentId, Long categoryId, Long contributorId,
                            String questionType, String workflowStatus,
                            String content, String explanation) {
        this.departmentId = departmentId;
        this.categoryId = categoryId;
        this.contributorId = contributorId;
        this.questionType = questionType;
        this.workflowStatus = workflowStatus;
        this.content = content;
        this.explanation = explanation;
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

    /** Updates author-editable content while keeping department ownership stable. */
    public void updateAuthoring(Long categoryId, String questionType,
                                String content, String explanation) {
        this.categoryId = categoryId;
        this.questionType = questionType;
        this.content = content;
        this.explanation = explanation;
    }

    /** Moves the item into a new workflow state and records the reviewer metadata. */
    public void transitionWorkflow(String workflowStatus, Long reviewedBy,
                                   String reviewNote, LocalDateTime reviewedAt,
                                   LocalDateTime approvedAt) {
        this.workflowStatus = workflowStatus;
        this.reviewedBy = reviewedBy;
        this.reviewNote = reviewNote;
        this.reviewedAt = reviewedAt;
        this.approvedAt = approvedAt;
    }

    /**
     * Archives the item, remembering the status it held so {@link #unarchive()}
     * can restore it exactly. Captures {@code statusBeforeArchive} only on the
     * first archive (guards against overwriting it if already ARCHIVED).
     */
    public void archive(Long reviewedBy, String reviewNote, LocalDateTime reviewedAt) {
        if (!STATUS_ARCHIVED.equals(this.workflowStatus)) {
            this.statusBeforeArchive = this.workflowStatus;
        }
        this.workflowStatus = STATUS_ARCHIVED;
        this.reviewedBy = reviewedBy;
        this.reviewNote = reviewNote;
        this.reviewedAt = reviewedAt;
    }

    /**
     * Restores the item to the status it held before archiving. Falls back to
     * REVIEW for legacy rows archived before {@code statusBeforeArchive} existed
     * (NULL). Clears the remembered status and stamps the restoring reviewer.
     */
    public void unarchive(Long reviewedBy, LocalDateTime reviewedAt) {
        this.workflowStatus = this.statusBeforeArchive != null
                ? this.statusBeforeArchive
                : STATUS_REVIEW;
        this.statusBeforeArchive = null;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public Long getContributorId() {
        return contributorId;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public String getQuestionType() {
        return questionType;
    }

    public String getWorkflowStatus() {
        return workflowStatus;
    }

    public String getStatusBeforeArchive() {
        return statusBeforeArchive;
    }

    public String getContent() {
        return content;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
