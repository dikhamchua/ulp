package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.RoleHierarchy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the {@code role_hierarchy} inheritance edges.
 *
 * <p>Edges are read-only for this feature: the seeded chain
 * {@code STUDENT <- LECTURER <- HEAD <- ADMIN} is migration-owned and the admin
 * screens never restructure it.
 */
@Repository
public interface RoleHierarchyRepository extends JpaRepository<RoleHierarchy, RoleHierarchy.Key> {

    /**
     * Loads the roles that directly inherit from the given role.
     *
     * <p>Only one level deep — callers that need the full descendant closure must
     * walk the result repeatedly.
     *
     * @param parentRoleCode the role whose immediate children are wanted
     * @return the inheritance edges leaving that role; never {@code null}
     */
    List<RoleHierarchy> findByIdParentRoleCode(String parentRoleCode);
}
