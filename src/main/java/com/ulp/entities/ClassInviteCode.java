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
 * JPA entity mapped to the {@code class_invite_codes} table.
 *
 * <p>Each row represents one invite token (either {@code CODE} or
 * {@code LINK}) belonging to a single class. The invariant
 * "at any instant a class has exactly one active row per type"
 * is enforced by {@code InviteCodeService} (service-level — MySQL
 * 8 does not support partial unique indexes).
 *
 * <p>Regenerating a token disables the previously active row
 * ({@code is_active=0}) and INSERTs a new active row, preserving
 * the full audit history. Rows are never deleted by the application
 * code path (V12's sentinel rows are an exception — the backfill
 * runner removes them after replacement).
 *
 * <p>Distinct from the 5-character {@code classes.code} class
 * identifier — see {@link ClassEntity} and {@code InviteTokenGenerator}
 * Javadoc for the namespace separation rationale.
 */
@Entity
@Table(name = "class_invite_codes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassInviteCode {

    /** Token type discriminator: 6-char invite code typed by hand. */
    public static final String TYPE_CODE = "CODE";

    /** Token type discriminator: 32-char base64url deep-link token. */
    public static final String TYPE_LINK = "LINK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 10)
    private String type;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private Integer useCount = 0;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Factory constructor for the create flow. Defaults
     * {@code isActive=true}, {@code useCount=0}, {@code maxUses=null}
     * (unlimited), {@code expiresAt=null} (never expires).
     *
     * @param classId   the owning class id
     * @param code      generated token value
     * @param type      either {@link #TYPE_CODE} or {@link #TYPE_LINK}
     * @param createdBy id of the user who provisioned the token
     */
    public ClassInviteCode(Long classId, String code, String type, Long createdBy) {
        this.classId = classId;
        this.code = code;
        this.type = type;
        this.active = true;
        this.useCount = 0;
        this.maxUses = null;
        this.expiresAt = null;
        this.createdBy = createdBy;
    }

    /**
     * Marks this token row as disabled. The row is retained in the
     * table for audit/history purposes; readers must filter by
     * {@code is_active = 1} to see live tokens.
     */
    public void disable() {
        this.active = false;
    }

    /**
     * Increments the {@code use_count} by one. Callers MUST hold a
     * pessimistic lock on this row (via
     * {@code ClassInviteCodeRepository#findByCodeForUpdate}) before
     * invoking this method so concurrent join attempts cannot
     * overshoot {@code max_uses}.
     */
    public void incrementUseCount() {
        this.useCount = (this.useCount == null ? 0 : this.useCount) + 1;
    }
}
