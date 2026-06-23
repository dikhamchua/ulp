package com.ulp.auth.entity;

import com.ulp.auth.Role;
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

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    // ── Sprint 1 additions ────────────────────────────────────────

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

    // ── Business helpers ───────────────────────────────────────────

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

    private static String blankToNull(String s) {
        return (s != null && s.isBlank()) ? null : s;
    }
}
