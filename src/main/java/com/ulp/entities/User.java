package com.ulp.entities;

import com.ulp.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code users} table.
 *
 * <p>Sprint 1 extensions add {@code bio}, {@code phone}, {@code avatarUrl}, and
 * {@code googleId} fields, along with an {@link #updateProfile} method for
 * user-editable profile data.
 *
 * <p>{@link SQLRestriction} ensures that every default query filters out
 * soft-deleted records ({@code is_deleted = 0}).
 */
@Entity
@Table(name = "users")
@SQLRestriction("is_deleted = 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Setter
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "is_email_verified")
    private boolean emailVerified;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "is_locked")
    private boolean locked = false;

    @Setter(AccessLevel.PACKAGE)
    @Column(name = "locked_reason", length = 255)
    private String lockedReason;

    @Column(name = "is_deleted")
    private boolean deleted = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // â”€â”€ Sprint 1 additions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Column(name = "bio")
    private String bio;

    @Column(name = "phone", length = 20)
    private String phone;

    @Setter
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Setter
    @Column(name = "google_id", length = 100)
    private String googleId;

    /** Owning department; optional for students / admins without a department. */
    @Setter
    @Column(name = "department_id")
    private Long departmentId;

    // ── Sprint 3 admin-side constructor ────────────────────────────────

    /**
     * Package-private constructor used by {@link UserFactory#newAdminCreated}.
     * The full set of mandatory create-time fields is supplied in one call so
     * the entity can never be persisted in a half-built state.
     */
    User(String email, String passwordHash, String fullName, Role role,
         boolean emailVerified, boolean active, boolean locked, boolean deleted,
         String phone, String bio) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.emailVerified = emailVerified;
        this.active = active;
        this.locked = locked;
        this.deleted = deleted;
        this.phone = phone;
        this.bio = bio;
    }

    // â”€â”€ Business helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Updates the profile fields that a user is allowed to edit directly.
     *
     * @param fullName the user's display name
     * @param bio      optional short biography; blank strings are stored as {@code null}
     * @param phone    optional phone number; blank strings are stored as {@code null}
     */
    public void updateProfile(String fullName, String bio, String phone) {
        this.fullName = fullName;
        this.bio = blankToNull(bio);
        this.phone = blankToNull(phone);
    }

    // â”€â”€ Sprint 3 admin-side business methods â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Sets the account's {@code is_active} flag.
     * Used by the admin Activate / Deactivate lifecycle actions; preferred over
     * exposing a public setter so the entity's mutation surface stays narrow.
     *
     * @param active new value for {@code is_active}
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Locks the account and records the disciplinary reason.
     * Both {@code is_locked} and {@code locked_reason} are set in one call so a
     * locked account can never exist without an attached reason.
     *
     * @param reason required, non-blank disciplinary reason
     */
    public void lock(String reason) {
        this.locked = true;
        this.lockedReason = reason;
    }

    /**
     * Unlocks the account and clears any previously recorded lock reason.
     */
    public void unlock() {
        this.locked = false;
        this.lockedReason = null;
    }

    /**
     * Marks the account as soft-deleted. Hibernate's {@code @SQLRestriction}
     * filter automatically hides this user from subsequent default queries.
     */
    public void softDelete() {
        this.deleted = true;
    }

    /**
     * Reverses a prior soft-delete. The caller must have loaded this entity
     * via the soft-delete-aware repository method
     * ({@code findByIdIncludingDeleted}); the default {@code findById} would
     * not have returned it because of the {@code @SQLRestriction} filter.
     */
    public void restore() {
        this.deleted = false;
    }

    /**
     * Bulk-updates the admin-editable identity and contact fields in one
     * transactional step. Email normalisation (trim + lowercase) is performed
     * by the caller before invoking this method.
     *
     * @param email          canonical, already-normalised email address
     * @param fullName       display name
     * @param role           target {@link Role}
     * @param emailVerified  whether the admin marks the email as verified
     * @param phone          optional phone number; blank strings stored as null
     * @param bio            optional short biography; blank strings stored as null
     */
    public void updateAdminFields(String email, String fullName, Role role,
                                  boolean emailVerified, String phone, String bio) {
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.emailVerified = emailVerified;
        this.phone = blankToNull(phone);
        this.bio = blankToNull(bio);
    }

    /**
     * Bulk-updates admin-editable fields including optional department assignment.
     *
     * @param email          canonical, already-normalised email address
     * @param fullName       display name
     * @param role           target {@link Role}
     * @param emailVerified  whether the admin marks the email as verified
     * @param phone          optional phone number; blank strings stored as null
     * @param bio            optional short biography; blank strings stored as null
     * @param departmentId   optional department ownership
     */
    public void updateAdminFields(String email, String fullName, Role role,
                                  boolean emailVerified, String phone, String bio,
                                  Long departmentId) {
        updateAdminFields(email, fullName, role, emailVerified, phone, bio);
        this.departmentId = departmentId;
    }

    /**
     * Promotes this user to HEAD of the given department.
     * Used by admin department head assignment.
     */
    public void promoteToHead(Long departmentId) {
        this.role = Role.HEAD;
        this.departmentId = departmentId;
    }

    /**
     * Demotes a former department head back to LECTURER.
     * Keeps {@code departmentId} so the user remains in their department.
     * Never demotes ADMIN.
     */
    public void demoteFromHeadToLecturer() {
        if (this.role != Role.ADMIN) {
            this.role = Role.LECTURER;
        }
    }

    private static String blankToNull(String s) {
        return (s != null && s.isBlank()) ? null : s;
    }
}
