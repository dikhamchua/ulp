package com.ulp.auth.repository;

import com.ulp.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository cho {@link User}. Nho {@code @SQLRestriction("is_deleted = 0")}
 * tren entity, moi truy van mac dinh da loai ban ghi soft-delete.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
}
