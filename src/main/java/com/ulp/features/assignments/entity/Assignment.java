package com.ulp.features.assignments.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity for the {@code assignments} table (V1 schema, section 10.1).
 *
 * <p>Status lifecycle: DRAFT → PUBLISHED → CLOSED. Transitions are enforced
 * in the service layer; this entity only carries the value.
 *
 * <p>{@code rubric} and {@code attachment_url} are out-of-scope for the MVP
 * and are left null.
 */
@Entity
@Table(name = "assignments")
@Getter
@Setter
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** Rubric JSON — left null for MVP (out-of-scope rubric scoring). */
    @Column(name = "rubric", columnDefinition = "JSON")
    private String rubric;

    @Column(name = "max_score", precision = 5, scale = 2)
    private BigDecimal maxScore;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Column(name = "allow_late_submission", nullable = false)
    private boolean allowLateSubmission;

    /** Attachment URL — left null for MVP (file upload out-of-scope). */
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    /**
     * Assignment lifecycle status. Valid values: DRAFT, PUBLISHED, CLOSED.
     * Enforced by a CHECK constraint in the DB and by the service state machine.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;
}
