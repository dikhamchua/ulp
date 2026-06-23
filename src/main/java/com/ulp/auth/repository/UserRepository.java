package com.ulp.auth.repository;

import com.ulp.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link User} entity.
 *
 * <p>Because the {@link User} entity is annotated with
 * {@code @SQLRestriction("is_deleted = 0")}, every query issued through this
 * repository automatically excludes soft-deleted records — no explicit filter
 * is required in derived queries or custom JPQL.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks up an active (non-deleted) user by their email address.
     *
     * @param email the email address to search for (case-sensitive, as stored)
     * @return an {@link Optional} containing the matching {@link User}, or
     *         {@link Optional#empty()} if no active user with that email exists
     */
    Optional<User> findByEmail(String email);
}
