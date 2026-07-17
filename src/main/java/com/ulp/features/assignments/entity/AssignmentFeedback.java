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
 * JPA entity for the {@code assignment_feedback} table (V1 schema, section 10.3).
 *
 * <p>A UNIQUE constraint on {@code submission_id} enforces one-feedback-per-submission.
 * The service layer upserts via this key (find-then-update or create).
 *
 * <p>{@code rubric_scores} and {@code is_ai_generated} are out-of-scope for the MVP
 * and stay at their DB defaults (null / 0).
 */
@Entity
@Table(name = "assignment_feedback")
@Getter
@Setter
public class AssignmentFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UNIQUE — enforces one feedback row per submission. */
    @Column(name = "submission_id", nullable = false, unique = true)
    private Long submissionId;

    @Column(name = "graded_by", nullable = false)
    private Long gradedBy;

    @Column(name = "score", nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    /** Rubric-level scores JSON — left null in MVP (out-of-scope). */
    @Column(name = "rubric_scores", columnDefinition = "JSON")
    private String rubricScores;

    /** Always 0 for MVP — AI grading is out-of-scope. */
    @Column(name = "is_ai_generated", nullable = false)
    private boolean aiGenerated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
