package com.ulp.features.auth.repository;

import com.ulp.features.admin.users.dto.AdminUsersDtos.UserRow;
import com.ulp.entities.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link User} entity.
 *
 * <p>Because {@link User} is annotated with {@code @SQLRestriction("is_deleted = 0")},
 * every default query (including derived queries and JPQL projections) silently
 * excludes soft-deleted records. Methods that NEED to see soft-deleted rows
 * (admin Restore, admin DELETED-filter list) are declared with explicit native
 * SQL to bypass the annotation.
 *
 * <p>Email lookups are case-insensitive across the application; the database
 * column uses {@code utf8mb4_unicode_ci} collation and the
 * {@code findByEmailIgnoreCase} derived query compiles to a
 * {@code LOWER(email) = LOWER(?)} comparison, so mixed-case input from any
 * code path resolves to the same row.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Looks up an active (non-deleted) user by their email address,
     * case-insensitively. Replaces the original {@code findByEmail} method to
     * eliminate the risk of case-mismatched lookups across the codebase.
     *
     * @param email the email address to search for (any case)
     * @return matching {@link User} or empty if no active user exists
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Returns the first non-deleted user whose email equals the argument
     * case-insensitively. Used by Create-form uniqueness checks.
     */
    Optional<User> findFirstByEmailIgnoreCase(String email);

    /**
     * Returns the first non-deleted user whose email equals the argument
     * case-insensitively and whose ID is not the supplied one. Used by
     * Edit-form uniqueness checks to allow the row being edited to keep
     * its current email.
     */
    Optional<User> findFirstByEmailIgnoreCaseAndIdNot(String email, Long id);

    /**
     * Counts active, non-deleted administrators.
     *
     * <p>Native SQL because the {@code @SQLRestriction} on the entity already
     * filters {@code is_deleted = 0} at the JPQL layer, but bypassing
     * Hibernate's restriction here lets the query stay explicit and
     * self-documenting â€” the WHERE clause shows every predicate the
     * last-admin guard relies on.
     *
     * @param role the role name (string) to count, typically {@code "ADMIN"}
     * @return number of users matching role AND is_active = 1 AND is_deleted = 0
     */
    @Query(value = "SELECT COUNT(*) FROM users " +
                   "WHERE role = :role AND is_active = 1 AND is_deleted = 0",
            nativeQuery = true)
    long countActiveAdmins(@Param("role") String role);

    /**
     * Loads a user by ID, INCLUDING soft-deleted rows.
     *
     * <p>Used by the admin Restore action which must see a row whose
     * {@code is_deleted = 1}. Defined as native SQL to bypass the entity's
     * {@code @SQLRestriction} filter.
     */
    @Query(value = "SELECT * FROM users WHERE id = ?1",
            nativeQuery = true)
    Optional<User> findByIdIncludingDeleted(Long id);

    /**
     * Locks a non-deleted user row for update inside the current transaction.
     *
     * <p>Two important constraints:
     * <ul>
     *   <li>The caller MUST be inside a {@code @Transactional} method;
     *       without an active transaction the JPA provider silently drops
     *       the pessimistic lock and the race window the caller is trying
     *       to close re-appears.</li>
     *   <li>This is a JPQL-derived query so the entity's
     *       {@code @SQLRestriction("is_deleted = 0")} STILL applies â€” it
     *       returns empty for soft-deleted users. This is the correct
     *       behaviour for the lifecycle actions (activate / deactivate /
     *       lock / unlock / reset-password / delete) that all operate on
     *       non-deleted users. The Restore action deliberately uses
     *       {@link #findByIdIncludingDeleted} instead.</li>
     * </ul>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /**
     * Paged search across the admin users list. Native SQL so an explicit
     * {@code is_deleted} predicate decides per status whether soft-deleted
     * rows are surfaced; ORDER BY translates {@code rolePriority} to a CASE
     * expression so {@code ADMIN} sorts first.
     *
     * @param q       optional case-insensitive substring matched against
     *                {@code full_name} or {@code email}; pass {@code null}
     *                or empty to disable
     * @param role    optional role filter; pass {@code null} or empty to disable
     * @param status  one of {@code "ACTIVE" | "INACTIVE" | "LOCKED" | "DELETED"};
     *                pass {@code null} for the default (non-deleted only)
     * @param pageable Spring page request (sort key handled by the
     *                  {@code ORDER BY} fragment built in JPA)
     */
    @Query(value = "SELECT u.id AS id, " +
                   "       u.full_name AS fullName, " +
                   "       u.email AS email, " +
                   "       u.role AS role, " +
                   "       u.is_active AS active, " +
                   "       u.is_locked AS locked, " +
                   "       u.is_deleted AS deleted, " +
                   "       u.department_id AS departmentId, " +
                   "       u.last_login_at AS lastLoginAt, " +
                   "       u.created_at AS createdAt, " +
                   "       u.avatar_url AS avatarUrl " +
                   "FROM users u " +
                   "WHERE (" +
                   "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                   "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                   "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                   "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                   "    (:status IS NULL      AND u.is_deleted = 0)" +
                   ") " +
                   "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                   "AND (:q IS NULL OR :q = '' OR " +
                   "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                   "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%'))",
            countQuery = "SELECT COUNT(*) FROM users u " +
                         "WHERE (" +
                         "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                         "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                         "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                         "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                         "    (:status IS NULL      AND u.is_deleted = 0)" +
                         ") " +
                         "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                         "AND (:q IS NULL OR :q = '' OR " +
                         "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                         "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%'))",
            nativeQuery = true)
    Page<UserRow> searchUsersForAdmin(@Param("q") String q,
                                      @Param("role") String role,
                                      @Param("status") String status,
                                      Pageable pageable);

    /**
     * Variant of {@link #searchUsersForAdmin} that sorts ASCENDING by role
     * priority: ADMIN (1) â†’ HEAD (2) â†’ LECTURER (3) â†’ STUDENT (4), then by
     * {@code created_at} DESC as tie-breaker. Pageable's own sort is ignored
     * for ordering purposes because Spring Data does not bind ORDER BY
     * direction or expressions as parameters.
     */
    @Query(value = "SELECT u.id AS id, " +
                   "       u.full_name AS fullName, " +
                   "       u.email AS email, " +
                   "       u.role AS role, " +
                   "       u.is_active AS active, " +
                   "       u.is_locked AS locked, " +
                   "       u.is_deleted AS deleted, " +
                   "       u.department_id AS departmentId, " +
                   "       u.last_login_at AS lastLoginAt, " +
                   "       u.created_at AS createdAt, " +
                   "       u.avatar_url AS avatarUrl " +
                   "FROM users u " +
                   "WHERE (" +
                   "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                   "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                   "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                   "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                   "    (:status IS NULL      AND u.is_deleted = 0)" +
                   ") " +
                   "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                   "AND (:q IS NULL OR :q = '' OR " +
                   "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                   "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%')) " +
                   "ORDER BY CASE u.role " +
                   "  WHEN 'ADMIN' THEN 1 WHEN 'HEAD' THEN 2 " +
                   "  WHEN 'LECTURER' THEN 3 ELSE 4 END ASC, u.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM users u " +
                         "WHERE (" +
                         "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                         "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                         "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                         "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                         "    (:status IS NULL      AND u.is_deleted = 0)" +
                         ") " +
                         "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                         "AND (:q IS NULL OR :q = '' OR " +
                         "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                         "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%'))",
            nativeQuery = true)
    Page<UserRow> searchUsersForAdminByRolePriorityAsc(@Param("q") String q,
                                                       @Param("role") String role,
                                                       @Param("status") String status,
                                                       Pageable pageable);

    /**
     * Variant of {@link #searchUsersForAdmin} that sorts DESCENDING by role
     * priority: STUDENT â†’ LECTURER â†’ HEAD â†’ ADMIN, then by {@code created_at}
     * DESC as tie-breaker. See {@link #searchUsersForAdminByRolePriorityAsc}.
     */
    @Query(value = "SELECT u.id AS id, " +
                   "       u.full_name AS fullName, " +
                   "       u.email AS email, " +
                   "       u.role AS role, " +
                   "       u.is_active AS active, " +
                   "       u.is_locked AS locked, " +
                   "       u.is_deleted AS deleted, " +
                   "       u.department_id AS departmentId, " +
                   "       u.last_login_at AS lastLoginAt, " +
                   "       u.created_at AS createdAt, " +
                   "       u.avatar_url AS avatarUrl " +
                   "FROM users u " +
                   "WHERE (" +
                   "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                   "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                   "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                   "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                   "    (:status IS NULL      AND u.is_deleted = 0)" +
                   ") " +
                   "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                   "AND (:q IS NULL OR :q = '' OR " +
                   "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                   "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%')) " +
                   "ORDER BY CASE u.role " +
                   "  WHEN 'ADMIN' THEN 1 WHEN 'HEAD' THEN 2 " +
                   "  WHEN 'LECTURER' THEN 3 ELSE 4 END DESC, u.created_at DESC",
            countQuery = "SELECT COUNT(*) FROM users u " +
                         "WHERE (" +
                         "    (:status = 'ACTIVE'   AND u.is_deleted = 0 AND u.is_active = 1 AND u.is_locked = 0) OR " +
                         "    (:status = 'INACTIVE' AND u.is_deleted = 0 AND u.is_active = 0 AND u.is_locked = 0) OR " +
                         "    (:status = 'LOCKED'   AND u.is_deleted = 0 AND u.is_locked = 1) OR " +
                         "    (:status = 'DELETED'  AND u.is_deleted = 1) OR " +
                         "    (:status IS NULL      AND u.is_deleted = 0)" +
                         ") " +
                         "AND (:role IS NULL OR :role = '' OR u.role = :role) " +
                         "AND (:q IS NULL OR :q = '' OR " +
                         "     LOWER(u.full_name) LIKE CONCAT('%', LOWER(:q), '%') OR " +
                         "     LOWER(u.email)     LIKE CONCAT('%', LOWER(:q), '%'))",
            nativeQuery = true)
    Page<UserRow> searchUsersForAdminByRolePriorityDesc(@Param("q") String q,
                                                        @Param("role") String role,
                                                        @Param("status") String status,
                                                        Pageable pageable);
}
