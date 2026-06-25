package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JPA entity mapping to the {@code user_oauth_providers} table.
 *
 * <p>Records the link between a {@link User} account and an external OAuth
 * provider (e.g. Google). A unique constraint on {@code (provider,
 * provider_user_id)} prevents duplicate bindings for the same external
 * identity.
 *
 * <p>Access/refresh token columns are intentionally omitted — YAGNI this sprint.
 */
@Entity
@Table(name = "user_oauth_providers",
       uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserOAuthProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /**
     * Creates a new OAuth provider binding for the given user.
     *
     * @param user           the local {@link User} account to link
     * @param provider       the OAuth provider name (e.g. {@code "google"})
     * @param providerUserId the unique user ID issued by the external provider
     */
    public UserOAuthProvider(User user, String provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
