package com.ulp.admin.users.entity;

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
 * JPA entity mapped to the {@code user_activities} table.
 * Represents an immutable audit-log entry for every administrative mutation
 * performed against a user account through the {@code /admin/users} screen.
 *
 * <p><b>Why plain {@code Long} for the foreign-key columns?</b> The
 * {@link com.ulp.auth.entity.User} entity carries
 * {@code @SQLRestriction("is_deleted = 0")}. A JPA {@code @ManyToOne}
 * association would route every read through that filter and silently drop
 * audit rows whose target user has been soft-deleted. Mirroring the
 * {@code ClassActivity} pattern from Sprint 2, we keep the FK as a bare
 * {@code Long} so the audit log remains intact regardless of the target
 * user's lifecycle.
 *
 * <p>Action {@link #type} taxonomy is closed; see the {@code TYPE_*}
 * constants below. Adding a new action type requires a follow-up change.
 */
@Entity
@Table(name = "user_activities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserActivity {

    public static final String TYPE_CREATED         = "CREATED";
    public static final String TYPE_UPDATED         = "UPDATED";
    public static final String TYPE_ROLE_CHANGED    = "ROLE_CHANGED";
    public static final String TYPE_DEACTIVATED     = "DEACTIVATED";
    public static final String TYPE_ACTIVATED       = "ACTIVATED";
    public static final String TYPE_LOCKED          = "LOCKED";
    public static final String TYPE_UNLOCKED        = "UNLOCKED";
    public static final String TYPE_PASSWORD_RESET  = "PASSWORD_RESET";
    public static final String TYPE_DELETED         = "DELETED";
    public static final String TYPE_RESTORED        = "RESTORED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

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

    public UserActivity(Long targetUserId, String type, String message,
                        String metadata, Long performedBy) {
        this.targetUserId = targetUserId;
        this.type = type;
        this.message = message;
        this.metadata = metadata;
        this.performedBy = performedBy;
    }
}
