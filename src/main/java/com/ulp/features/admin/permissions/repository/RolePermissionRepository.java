package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.RolePermission;
import com.ulp.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code role_permissions} join table.
 *
 * <p>Rows record direct grants only. A role that merely inherits a permission
 * through {@code role_hierarchy} has no row here, so callers must not treat the
 * absence of a row as "the role cannot use this permission".
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {

    /**
     * Loads every direct grant for one role.
     *
     * @param roleCode the role code (e.g. {@code LECTURER})
     * @return the role's direct grants; never {@code null}
     */
    List<RolePermission> findByIdRoleCode(String roleCode);

    /**
     * Looks up a single direct grant.
     *
     * @param roleCode     the role code
     * @param permissionId the {@code permissions.id}
     * @return the grant row, or empty when the role does not hold it directly
     */
    Optional<RolePermission> findByIdRoleCodeAndIdPermissionId(String roleCode, Long permissionId);

    /**
     * Reports whether a role directly holds a permission.
     *
     * @param roleCode     the role code
     * @param permissionId the {@code permissions.id}
     * @return {@code true} when a direct grant row exists
     */
    boolean existsByIdRoleCodeAndIdPermissionId(String roleCode, Long permissionId);

    /**
     * Returns the ids of every user whose role is one of the supplied codes.
     *
     * <p>Drives role-scoped cache eviction: after a role's permission set changes,
     * each affected user's cached permission entry must be dropped individually
     * because the cache is keyed by user id.
     *
     * @param roleCodes the role codes whose members should be collected
     * @return matching user ids; never {@code null}
     */
    @Query("SELECT u.id FROM User u WHERE u.role IN :roleCodes")
    List<Long> findUserIdsByRoleCodes(@Param("roleCodes") List<Role> roleCodes);
}
