package com.ulp.features.admin.permissions.repository;

import com.ulp.entities.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Read-oriented repository for the {@code permissions} catalogue.
 *
 * <p>The catalogue is owned by Flyway migrations; nothing in this feature inserts
 * or deletes rows here. Only lookups are exposed.
 */
@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    /**
     * Loads the whole catalogue ordered for matrix rendering.
     *
     * @return every permission sorted by group then feature key
     */
    List<Permission> findAllByOrderByPermissionGroupAscFeatureKeyAsc();

    /**
     * Looks up a permission by its unique feature key.
     *
     * @param featureKey the feature key (e.g. {@code library.manage})
     * @return the matching permission, or empty when the key is unknown
     */
    Optional<Permission> findByFeatureKey(String featureKey);

    /**
     * Loads every permission belonging to one of the given groups.
     *
     * @param permissionGroups the groups to include (e.g. {@code SYSTEM}, {@code USER_MANAGE})
     * @return matching permissions; never {@code null}
     */
    List<Permission> findByPermissionGroupIn(List<String> permissionGroups);
}
