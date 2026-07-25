package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.Permission;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read-only access to the role side of the RBAC schema.
 *
 * <p>Extends the marker {@link Repository} rather than {@code JpaRepository} because
 * nothing here is writable — only the projection query below is exposed, so no caller
 * can accidentally attempt a save or delete through it.
 *
 * <p>The query is native: a recursive CTE has no JPQL equivalent. Columns are aliased
 * to camelCase so Spring Data can bind them to {@link RolePermissionRow}'s getters.
 */
public interface EffectivePermissionRepository extends Repository<Permission, Long> {

    /**
     * Reads the permissions a user derives from their role, with inheritance expanded.
     *
     * <p>Deliberately reads {@code role_permissions} through the same recursive
     * {@code role_hierarchy} walk the view performs, but stops short of applying
     * overrides. Override precedence and expiry are decided in Java by
     * {@code PermissionResolver} instead, because {@code expires_at} holds a zoneless
     * {@code LocalDateTime} written by the JVM while the view compared it against the
     * database server's {@code NOW()}. When the two clocks sit in different zones that
     * comparison silently misjudges expiry — an elapsed temporary GRANT kept granting
     * access for the width of the offset. Every other expiry in this codebase
     * ({@code PasswordResetToken}, {@code PublicViewToken}) is evaluated in Java for
     * the same reason.
     *
     * @param userId the user whose role-derived permissions are wanted
     * @return one row per permission the user's role chain grants; never {@code null}
     */
    @Query(value = "WITH RECURSIVE role_tree AS ("
            + "  SELECT u.role AS role_code, u.role AS inherited_from"
            + "  FROM users u WHERE u.id = :userId"
            + "  UNION ALL"
            + "  SELECT rt.role_code, rh.parent_role_code"
            + "  FROM role_tree rt"
            + "  JOIN role_hierarchy rh ON rt.inherited_from = rh.child_role_code"
            + ") "
            + "SELECT DISTINCT p.id AS permissionId, "
            + "p.feature_key AS featureKey, "
            + "p.permission_group AS permissionGroup "
            + "FROM role_tree rt "
            + "JOIN role_permissions rp ON rp.role_code = rt.inherited_from "
            + "JOIN permissions p ON p.id = rp.permission_id",
            nativeQuery = true)
    List<RolePermissionRow> findRoleDerivedPermissions(@Param("userId") Long userId);
}
