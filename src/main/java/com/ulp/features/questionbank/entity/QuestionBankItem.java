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
 * A question-bank item organised by subject (required) + chapter (optional) and
 * owned by one of two scopes: the HEAD bank ({@code ownerId == null},
 * department-owned) or a lecturer-private bank ({@code ownerId = user.id}).
 *
 * <p>There is no review workflow: items are created {@link #STATUS_ACTIVE} and
 * only {@link #STATUS_ARCHIVED} hides them from the exam picker.
 */
@Entity
@Table(name = "question_bank_items")
public class QuestionBankItem {

    public static final String TYPE_MCQ = "MCQ";
    public static final String TYPE_MR = "MR";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    /** Null = HEAD bank item; non-null = lecturer-private bank item. */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "chapter_id")
    private Long chapterId;

    @Column(name = "contributor_id", nullable = false)
    private Long contributorId;

    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    @Column(nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "status_before_archive", length = 20)
    private String statusBeforeArchive;

    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected QuestionBankItem() {
    }

    /**
     * Creates a bank item. {@code ownerId} is {@code null} for the HEAD bank and
     * the owning lecturer's user id for a private bank; the item is created
     * {@link #STATUS_ACTIVE}.
     */
    public QuestionBankItem(Long departmentId, Long subjectId, Long ownerId, Long chapterId,
                            Long contributorId, String questionType, String status,
                            String content, String explanation) {
        this.departmentId = departmentId;
        this.subjectId = subjectId;
        this.ownerId = ownerId;
        this.chapterId = chapterId;
        this.contributorId = contributorId;
        this.questionType = questionType;
        this.status = status;
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

    /** Updates author-editable content while keeping ownership stable. */
    public void updateAuthoring(Long subjectId, Long chapterId, String questionType,
                                String content, String explanation) {
        this.subjectId = subjectId;
        this.chapterId = chapterId;
        this.questionType = questionType;
        this.content = content;
        this.explanation = explanation;
    }

    /**
     * Archives the item, remembering the status it held so {@link #unarchive()}
     * can restore it exactly. Captures {@code statusBeforeArchive} only on the
     * first archive (guards against overwriting it if already ARCHIVED).
     */
    public void archive() {
        if (!STATUS_ARCHIVED.equals(this.status)) {
            this.statusBeforeArchive = this.status;
        }
        this.status = STATUS_ARCHIVED;
    }

    /**
     * Restores the item to the status it held before archiving. Falls back to
     * ACTIVE for legacy rows archived before {@code statusBeforeArchive} existed
     * (NULL), then clears the remembered status.
     */
    public void unarchive() {
        this.status = this.statusBeforeArchive != null
                ? this.statusBeforeArchive
                : STATUS_ACTIVE;
        this.statusBeforeArchive = null;
    }

    public Long getId() {
        return id;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public Long getContributorId() {
        return contributorId;
    }

    public String getQuestionType() {
        return questionType;
    }

    public String getStatus() {
        return status;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
