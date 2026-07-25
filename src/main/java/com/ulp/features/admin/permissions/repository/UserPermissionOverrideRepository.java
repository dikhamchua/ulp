package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.UserPermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@code user_permission_overrides}.
 *
 * <p>No delete method is exposed on purpose: an override that stops applying is
 * deactivated so the row survives as history.
 */
@Repository
public interface UserPermissionOverrideRepository extends JpaRepository<UserPermissionOverride, Long> {

    /**
     * Finds the single override row for a user + permission pair.
     *
     * <p>The table's {@code idx_upo_user_perm} unique index guarantees at most one
     * row, active or not. Callers updating an override must reuse this row rather
     * than inserting a second one.
     *
     * @param userId       the user the override belongs to
     * @param permissionId the {@code permissions.id}
     * @return the existing row, or empty when the pair has never been overridden
     */
    Optional<UserPermissionOverride> findByUserIdAndPermissionId(Long userId, Long permissionId);

    /**
     * Loads every override recorded for one user, including inactive and expired ones.
     *
     * @param userId the user whose overrides are wanted
     * @return the user's override history, newest first; never {@code null}
     */
    List<UserPermissionOverride> findByUserIdOrderByIdDesc(Long userId);

    /**
     * Loads the whole override list for the admin screen, newest first.
     *
     * <p>Inactive and expired rows are included so the page can mark them rather
     * than hide them.
     *
     * @return every override row; never {@code null}
     */
    List<UserPermissionOverride> findAllByOrderByIdDesc();
}
