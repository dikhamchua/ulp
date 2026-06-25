package com.ulp.features.auth.repository;

import com.ulp.entities.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for managing {@link PasswordResetToken} entities.
 *
 * <p>Provides standard CRUD operations via {@link JpaRepository} and a
 * custom lookup method for retrieving a token record by its raw token string,
 * used during the password-reset verification flow.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);
}
