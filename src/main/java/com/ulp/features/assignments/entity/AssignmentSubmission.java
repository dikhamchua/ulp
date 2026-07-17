package com.ulp.features.assignments.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity for the {@code assignment_submissions} table (V1 schema, section 10.2).
 *
 * <p>A UNIQUE index on (assignment_id, user_id) guarantees at most one submission
 * row per student per assignment. The service layer upserts via this key.
 *
 * <p>Status values: DRAFT, SUBMITTED, GRADED. Editing is refused once GRADED.
 */
@Entity
@Table(name = "assignment_submissions")
@Getter
@Setter
public class AssignmentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Student's text/link answer — may be null if the student only provided an attachment. */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** Attachment URL — left null in MVP (file upload out-of-scope). */
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    /**
     * Submission lifecycle status. Valid values: DRAFT, SUBMITTED, GRADED.
     * The service sets GRADED when a feedback row is saved.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** True when the student submitted after the assignment's due date. */
    @Column(name = "is_late", nullable = false)
    private boolean late;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
