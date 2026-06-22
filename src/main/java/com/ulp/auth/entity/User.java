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
 * Entity map bang {@code users}. Sprint 1 mo rong: bio, phone, avatarUrl, googleId
 * va phuong thuc update cho cac truong nguoi dung co the chinh sua.
 *
 * <p>{@link SQLRestriction} dam bao moi truy van mac dinh chi lay ban ghi
 * chua bi soft-delete (is_deleted = 0).
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

    // ── Sprint 1 additions ─────────────────────────────────────────

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

    /** Cap nhat cac truong nguoi dung co the tu chinh sua trong profile. */
    public void updateProfile(String fullName, String bio, String phone) {
        this.fullName = fullName;
        this.bio = blankToNull(bio);
        this.phone = blankToNull(phone);
    }

    private static String blankToNull(String s) {
        return (s != null && s.isBlank()) ? null : s;
    }
}
