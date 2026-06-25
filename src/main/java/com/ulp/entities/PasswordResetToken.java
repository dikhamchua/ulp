package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code password_reset_tokens} table.
 *
 * <p>Represents a single-use token issued during the forgot-password flow.
 * A token is considered valid only when it has not been used and has not
 * yet passed its expiry timestamp (typically 1 hour after issuance).
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 128, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /**
     * Creates a new password-reset token for the given user.
     *
     * @param user      the account owner requesting the password reset
     * @param token     the raw token string (should be cryptographically random)
     * @param expiresAt the point in time after which this token is no longer valid
     */
    public PasswordResetToken(User user, String token, LocalDateTime expiresAt) {
        this.user = user;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    // ── Business logic ─────────────────────────────────────

    /**
     * Returns {@code true} if this token has already been consumed.
     *
     * @return {@code true} when {@code usedAt} is non-null
     */
    public boolean isUsed() { return usedAt != null; }

    /**
     * Returns {@code true} if the current time is past the token's expiry timestamp.
     *
     * @return {@code true} when the token has expired
     */
    public boolean isExpired() { return LocalDateTime.now().isAfter(expiresAt); }

    /**
     * Returns {@code true} if this token can still be used to reset a password.
     *
     * <p>A token is valid when it has not been used and has not yet expired.
     *
     * @return {@code true} when the token is both unused and within its validity window
     */
    public boolean isValid() { return !isUsed() && !isExpired(); }

    /**
     * Marks this token as consumed by recording the current timestamp in {@code usedAt}.
     *
     * <p>Should be called immediately after a successful password reset to prevent
     * the same token from being reused.
     */
    public void markUsed() {
        this.usedAt = LocalDateTime.now();
    }
}
