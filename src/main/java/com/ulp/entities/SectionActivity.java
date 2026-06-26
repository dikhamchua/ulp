package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code activity_sections} table.
 * Represents an immutable audit-log entry for every mutation performed on a
 * {@link Section}: CREATED, RENAMED, REORDERED, DELETED.
 *
 * <p><b>Important:</b> {@code sectionId} is stored as a plain {@code Long}
 * rather than a {@code @ManyToOne} association to {@link Section}. This
 * mirrors the {@link ClassActivity} design: {@code Section} carries a
 * {@code @SQLRestriction("is_deleted = 0")} filter, so a JPA join would
 * silently exclude rows whose target section has been soft-deleted. Keeping
 * the foreign key as a bare {@code Long} ensures the audit log remains
 * intact and independent of the section lifecycle.
 */
@Entity
@Table(name = "activity_sections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SectionActivity {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_RENAMED = "RENAMED";
    public static final String TYPE_REORDERED = "REORDERED";
    public static final String TYPE_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public SectionActivity(Long sectionId, String type, String description,
                           String metadata, Long createdBy) {
        this.sectionId = sectionId;
        this.type = type;
        this.description = description;
        this.metadata = metadata;
        this.createdBy = createdBy;
    }
}
