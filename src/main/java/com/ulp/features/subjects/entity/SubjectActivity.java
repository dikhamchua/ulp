package com.ulp.features.subjects.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Immutable audit row for subject catalog mutations.
 * Actor is a bare user id so history survives user deletion.
 */
@Entity
@Table(name = "subject_activities")
public class SubjectActivity {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_UPDATED = "UPDATED";
    public static final String TYPE_ACTIVATED = "ACTIVATED";
    public static final String TYPE_DEACTIVATED = "DEACTIVATED";
    public static final String TYPE_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SubjectActivity() {
    }

    public SubjectActivity(Long subjectId, String type, String message,
                           String metadata, Long performedBy) {
        this.subjectId = subjectId;
        this.type = type;
        this.message = message;
        this.metadata = metadata;
        this.performedBy = performedBy;
    }

    public Long getId() {
        return id;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getMetadata() {
        return metadata;
    }

    public Long getPerformedBy() {
        return performedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
