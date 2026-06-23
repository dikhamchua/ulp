package com.ulp.admin.users.repository;

import com.ulp.admin.users.entity.UserActivity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link UserActivity}.
 *
 * <p>Append-only by convention. No update or delete operations are exposed
 * here on top of the inherited {@link JpaRepository} surface; callers should
 * only ever insert via {@link JpaRepository#save(Object)}.
 */
public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {
}
