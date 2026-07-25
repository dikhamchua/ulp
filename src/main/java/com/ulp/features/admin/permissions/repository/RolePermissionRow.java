package com.ulp.features.admin.permissions.repository;

/**
 * Spring Data interface projection over one permission a user derives from their role.
 *
 * <p>Produced by {@link EffectivePermissionRepository#findRoleDerivedPermissions(Long)}
 * after role inheritance has been expanded through {@code role_hierarchy}. Overrides
 * are not reflected here: {@code PermissionResolver} layers those on afterwards so
 * expiry is judged against the JVM clock.
 */
public interface RolePermissionRow {

    /**
     * @return the {@code permissions.id}, used to match override rows
     */
    Long getPermissionId();

    /**
     * @return the permission's feature key (e.g. {@code library.manage})
     */
    String getFeatureKey();

    /**
     * @return the permission group the key belongs to (e.g. {@code LIBRARY})
     */
    String getPermissionGroup();
}
