package com.ulp.features.auth.repository;

import com.ulp.entities.UserOAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for managing {@link UserOAuthProvider} entities.
 *
 * <p>Provides data access operations for OAuth provider bindings associated with user accounts.
 * Extends {@link org.springframework.data.jpa.repository.JpaRepository} to inherit standard
 * CRUD operations.
 */
public interface UserOAuthProviderRepository extends JpaRepository<UserOAuthProvider, Long> {

    Optional<UserOAuthProvider> findByProviderAndProviderUserId(String provider, String providerUserId);
}
