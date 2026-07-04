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
 * JPA entity mapped to the {@code activity_tests} table (see V3 migration).
 * Immutable audit-log entry for every mutation a lecturer performs against an
 * exam through the {@code /lecturer/tests} detail screen — mirrors the
 * {@link UserActivity} pattern used by the admin user-management feature.
 *
 * <p>The foreign-key columns are bare {@code Long}s (not {@code @ManyToOne})
 * so audit rows survive regardless of the referenced test's or actor's
 * lifecycle, exactly as {@code UserActivity} does for soft-deleted users.
 *
 * <p>Action {@link #type} taxonomy is closed; see the {@code TYPE_*} constants.
 */
@Entity
@Table(name = "activity_tests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestActivity {

    public static final String TYPE_CREATED   = "CREATED";
    public static final String TYPE_UPDATED   = "UPDATED";
    public static final String TYPE_PUBLISHED = "PUBLISHED";
    public static final String TYPE_CLOSED    = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_id", nullable = false)
    private Long testId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "json")
    private String metadata;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public TestActivity(Long testId, String type, String description,
                        String metadata, Long createdBy) {
        this.testId = testId;
        this.type = type;
        this.description = description;
        this.metadata = metadata;
        this.createdBy = createdBy;
    }
}
