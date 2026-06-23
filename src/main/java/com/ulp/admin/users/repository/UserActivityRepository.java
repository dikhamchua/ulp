package com.ulp.admin.users.repository;

import com.ulp.admin.users.dto.ActivityRow;
import com.ulp.admin.users.entity.UserActivity;
import com.ulp.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link UserActivity}.
 *
 * <p>Append-only by convention. No update or delete operations are exposed
 * here on top of the inherited {@link JpaRepository} surface; callers should
 * only ever insert via {@link JpaRepository#save(Object)}.
 */
public interface UserActivityRepository extends JpaRepository<UserActivity, Long> {

    /**
     * Paged audit history for a single target user, projected into
     * {@link ActivityRow} so the template renders without per-row N+1 lookups.
     *
     * <p>The LEFT JOIN against {@link User} resolves the actor's email in the
     * same query; rows whose {@code performed_by} is {@code null} (e.g. when
     * the actor has been hard-deleted) surface with {@code actorEmail = null}.
     *
     * <p>Note: the join uses Hibernate 6's "JOIN target ON ..." syntax with no
     * mapped association between {@link UserActivity} and {@link User}; the FK
     * column is intentionally a bare {@code Long} on the entity so that audit
     * rows for soft-deleted target users remain visible (see entity javadoc).
     *
     * @param targetUserId the user the audit rows are about
     * @param pageable     paging directives — {@code Sort} is ignored because
     *                     the query hard-codes {@code ORDER BY a.createdAt DESC}
     * @return one page of {@link ActivityRow}, newest first
     */
    @Query("SELECT new com.ulp.admin.users.dto.ActivityRow(" +
           "  a.id, a.type, a.message, u.email, a.createdAt) " +
           "FROM UserActivity a " +
           "LEFT JOIN User u ON u.id = a.performedBy " +
           "WHERE a.targetUserId = :targetUserId " +
           "ORDER BY a.createdAt DESC, a.id DESC")
    Page<ActivityRow> findActivitiesForTargetUser(@Param("targetUserId") Long targetUserId,
                                                  Pageable pageable);
}
