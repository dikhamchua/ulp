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
 * JPA entity mapping the {@code user_permission_overrides} table
 * (see {@code V4__rbac_enhancement.sql}).
 *
 * <p>An override adjusts one user's effective permissions relative to what their
 * role grants: {@code GRANT} adds a permission the role lacks, {@code REVOKE}
 * suppresses one the role would otherwise supply. Precedence is
 * {@code REVOKE > GRANT > FROM_ROLE}.
 *
 * <p>A row is only in effect when {@code isActive} is true AND {@code expiresAt}
 * is either null or still in the future. Rows that fail either test are ignored
 * at read time and deliberately kept in the database as history — nothing in the
 * resolution path deletes or rewrites them.
 *
 * <p>The table carries {@code UNIQUE INDEX idx_upo_user_perm (user_id, permission_id)},
 * so a second override for the same pair must update the existing row rather than
 * insert a new one.
 *
 * <p>Deliberately not annotated with {@code @Data}.
 */
@Entity
@Table(name = "user_permission_overrides")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPermissionOverride {

    /** Override that adds a permission the user's role does not grant. */
    public static final String TYPE_GRANT = "GRANT";

    /** Override that suppresses a permission the user's role would grant. */
    public static final String TYPE_REVOKE = "REVOKE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    @Column(name = "override_type", nullable = false, length = 10)
    private String overrideType;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Maintained by MySQL {@code ON UPDATE CURRENT_TIMESTAMP}; never written by Hibernate. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a new active override.
     *
     * @param userId       the user the override applies to
     * @param permissionId the {@code permissions.id} being granted or revoked
     * @param overrideType {@link #TYPE_GRANT} or {@link #TYPE_REVOKE}
     * @param reason       mandatory justification recorded for audit
     * @param grantedBy    the acting admin's user id
     * @param expiresAt    expiry instant, or {@code null} for a permanent override
     */
    public UserPermissionOverride(Long userId, Long permissionId, String overrideType,
                                  String reason, Long grantedBy, LocalDateTime expiresAt) {
        this.userId = userId;
        this.permissionId = permissionId;
        this.overrideType = overrideType;
        this.reason = reason;
        this.grantedBy = grantedBy;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    /**
     * Overwrites this row with newly submitted values and reactivates it.
     *
     * <p>Used when an admin submits an override for a user + permission pair that
     * already has a row: updating in place is what keeps the unique index
     * {@code idx_upo_user_perm} satisfied without a delete-then-insert.
     *
     * @param overrideType {@link #TYPE_GRANT} or {@link #TYPE_REVOKE}
     * @param reason       mandatory justification recorded for audit
     * @param grantedBy    the acting admin's user id
     * @param expiresAt    expiry instant, or {@code null} for a permanent override
     */
    public void replaceWith(String overrideType, String reason,
                            Long grantedBy, LocalDateTime expiresAt) {
        this.overrideType = overrideType;
        this.reason = reason;
        this.grantedBy = grantedBy;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    /** Marks the override as no longer in effect while keeping the row as history. */
    public void deactivate() {
        this.active = false;
    }

    /**
     * Reports whether this override currently applies.
     *
     * @param now the instant to evaluate expiry against
     * @return {@code true} when active and either permanent or not yet expired
     */
    public boolean isInEffect(LocalDateTime now) {
        // A null expiry means the override never expires.
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }
}
