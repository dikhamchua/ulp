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
 * JPA entity mapping the {@code permission_activities} audit table
 * (see {@code V36__rbac_permissions_backfill.sql}).
 *
 * <p>One row per accepted RBAC change. Two event shapes share the table:
 * matrix changes are role-scoped and carry {@code roleCode} with a null
 * {@code targetUserId}, while override writes are user-scoped and carry
 * {@code targetUserId}. {@code featureKey} is set for both.
 *
 * <p>Rejected changes are never recorded — the guard throws before the writer runs.
 *
 * <p>Deliberately not annotated with {@code @Data} — Lombok's generated
 * equals/hashCode on a JPA entity causes identity cycles.
 */
@Entity
@Table(name = "permission_activities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionActivity {

    // ── Event types ───────────────────────────────────────────────
    public static final String TYPE_ROLE_PERMISSION_ATTACHED = "ROLE_PERMISSION_ATTACHED";
    public static final String TYPE_ROLE_PERMISSION_DETACHED = "ROLE_PERMISSION_DETACHED";
    public static final String TYPE_OVERRIDE_CREATED         = "OVERRIDE_CREATED";
    public static final String TYPE_OVERRIDE_REPLACED        = "OVERRIDE_REPLACED";
    public static final String TYPE_OVERRIDE_DEACTIVATED     = "OVERRIDE_DEACTIVATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "role_code", length = 20)
    private String roleCode;

    @Column(name = "feature_key", length = 100)
    private String featureKey;

    @Column(name = "target_user_id")
    private Long targetUserId;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "performed_by")
    private Long performedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Creates an audit row for an accepted RBAC change.
     *
     * @param type         one of the {@code TYPE_*} constants
     * @param roleCode     the affected role, or {@code null} for a user override
     * @param featureKey   the affected permission's feature key
     * @param targetUserId the affected user, or {@code null} for a matrix change
     * @param message      human-readable summary shown in audit listings
     * @param metadata     serialized JSON payload, or {@code null}
     * @param performedBy  the acting admin's user id
     */
    public PermissionActivity(String type, String roleCode, String featureKey, Long targetUserId,
                              String message, String metadata, Long performedBy) {
        this.type = type;
        this.roleCode = roleCode;
        this.featureKey = featureKey;
        this.targetUserId = targetUserId;
        this.message = message;
        this.metadata = metadata;
        this.performedBy = performedBy;
    }
}
