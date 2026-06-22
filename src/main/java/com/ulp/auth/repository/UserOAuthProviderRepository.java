package com.ulp.auth.repository;

import com.ulp.auth.entity.UserOAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Repository cho {@link UserOAuthProvider}. */
public interface UserOAuthProviderRepository extends JpaRepository<UserOAuthProvider, Long> {

    Optional<UserOAuthProvider> findByProviderAndProviderUserId(String provider, String providerUserId);
}
