package com.ulp.features.subjects.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code subjects} catalog table (môn học).
 *
 * <p>Each subject belongs to exactly one department. Soft-deleted rows are
 * excluded from default queries via {@link SQLRestriction}.
 */
@Entity
@Table(name = "subjects")
@SQLRestriction("is_deleted = 0")
public class Subject {

    /**
     * Reserved placeholder code seeded per department for legacy backfill.
     *
     * <p>V50 retired it by soft-delete, which nulls {@code live_code} and so
     * frees the {@code (department_id,'UNASSIGNED')} slot on
     * {@code uk_subjects_dept_live_code}. The database therefore no longer
     * stops a new one: {@code SubjectService} guards this code instead.
     */
    public static final String CODE_UNASSIGNED = "UNASSIGNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_id", nullable = false)
    private Long departmentId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    /** JPA-only constructor. */
    protected Subject() {
    }

    /**
     * Creates a new subject ready to persist.
     *
     * @param departmentId owning department
     * @param code         short code unique within the department
     * @param title        display title
     * @param description  optional description
     * @param active       whether the subject appears in class pickers
     * @param createdBy    actor user id, or null
     */
    public Subject(Long departmentId, String code, String title,
                   String description, boolean active, Long createdBy) {
        this.departmentId = departmentId;
        this.code = code;
        this.title = title;
        this.description = description;
        this.active = active;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Applies edited catalog fields. Caller validates uniqueness first. */
    public void applyEdit(String code, String title, String description, boolean active) {
        this.code = code;
        this.title = title;
        this.description = description;
        this.active = active;
    }

    /** Flips the active flag and returns the new state. */
    public boolean toggleActive() {
        this.active = !this.active;
        return this.active;
    }

    /** Soft-deletes this subject so default queries exclude it. */
    public void softDelete() {
        this.deleted = true;
    }

    public Long getId() {
        return id;
    }

    public Long getDepartmentId() {
        return departmentId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
