package com.ulp.features.admin.permissions.service;

import com.ulp.config.CacheConfig;
import com.ulp.entities.Permission;
import com.ulp.entities.RoleHierarchy;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.EffectivePermissionRepository;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.RoleHierarchyRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRow;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.security.Role;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the effective permission set of a single user.
 *
 * <p>Reads the user's role-derived permissions with inheritance already expanded through
 * {@code role_hierarchy}, then layers overrides on top in Java. The net rule is
 * {@code REVOKE > GRANT > FROM_ROLE}.
 *
 * <p>Expiry is deliberately evaluated here rather than in SQL. {@code expires_at} stores
 * a zoneless {@code LocalDateTime} written by the JVM, so comparing it against the
 * database server's {@code NOW()} misjudges expiry whenever the two clocks sit in
 * different zones — an elapsed temporary GRANT would keep granting access for the width
 * of the offset. Judging it against {@link LocalDateTime#now()} keeps this consistent
 * with {@code PasswordResetToken} and {@code PublicViewToken}.
 *
 * <p>Nothing here mutates override rows. Expired and deactivated overrides stay in the
 * database as history and are simply not reflected in the resolved set.
 *
 * <p>Results are cached per user id in {@link CacheConfig#CACHE_USER_PERMISSIONS}.
 * Callers that change a role's permissions or a user's overrides must invoke the
 * matching eviction method so the next resolution reads fresh data.
 */
@Service
public class PermissionResolver {

    private final EffectivePermissionRepository effectivePermissionRepository;
    private final RoleHierarchyRepository roleHierarchyRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final PermissionRepository permissionRepository;
    private final CacheManager cacheManager;

    public PermissionResolver(EffectivePermissionRepository effectivePermissionRepository,
                              RoleHierarchyRepository roleHierarchyRepository,
                              RolePermissionRepository rolePermissionRepository,
                              UserPermissionOverrideRepository overrideRepository,
                              PermissionRepository permissionRepository,
                              CacheManager cacheManager) {
        this.effectivePermissionRepository = effectivePermissionRepository;
        this.roleHierarchyRepository = roleHierarchyRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.overrideRepository = overrideRepository;
        this.permissionRepository = permissionRepository;
        this.cacheManager = cacheManager;
    }

    /**
     * Resolves the feature keys the given user effectively holds.
     *
     * @param userId the user to resolve; {@code null} yields an empty set
     * @return the effective feature keys, insertion-ordered; never {@code null}
     */
    // condition guards the null case: Spring rejects a null cache key outright, which
    // would turn the documented empty-set contract into an IllegalArgumentException.
    @Cacheable(value = CacheConfig.CACHE_USER_PERMISSIONS, key = "#userId",
            condition = "#userId != null")
    @Transactional(readOnly = true)
    public Set<String> resolvePermissions(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        List<RolePermissionRow> roleRows = effectivePermissionRepository.findRoleDerivedPermissions(userId);

        // One clock for every expiry decision below, so rows cannot be judged against
        // slightly different instants within a single resolution.
        LocalDateTime now = LocalDateTime.now();

        // Split the user's overrides by permission id. The unique index allows one row
        // per pair, but expired and deactivated rows are kept as history, so each row
        // still has to pass isInEffect() before it counts.
        Set<Long> granted = new LinkedHashSet<>();
        Set<Long> revoked = new LinkedHashSet<>();
        for (UserPermissionOverride override : overrideRepository.findByUserIdOrderByIdDesc(userId)) {
            if (!override.isInEffect(now)) {
                continue;
            }
            if (UserPermissionOverride.TYPE_REVOKE.equals(override.getOverrideType())) {
                revoked.add(override.getPermissionId());
            } else if (UserPermissionOverride.TYPE_GRANT.equals(override.getOverrideType())) {
                granted.add(override.getPermissionId());
            }
        }

        Set<String> featureKeys = new LinkedHashSet<>();
        for (RolePermissionRow row : roleRows) {
            // REVOKE beats the role grant.
            if (!revoked.contains(row.getPermissionId())) {
                featureKeys.add(row.getFeatureKey());
                granted.remove(row.getPermissionId());
            }
        }

        // Whatever GRANT rows remain add permissions the role chain never supplied.
        if (!granted.isEmpty()) {
            for (Permission permission : permissionRepository.findAllById(granted)) {
                if (!revoked.contains(permission.getId())) {
                    featureKeys.add(permission.getFeatureKey());
                }
            }
        }
        return featureKeys;
    }

    /**
     * Drops the cached permission set of one user.
     *
     * @param userId the user whose next resolution should re-read the database
     */
    @CacheEvict(value = CacheConfig.CACHE_USER_PERMISSIONS, key = "#userId")
    public void evictUser(Long userId) {
        // Body intentionally empty — the annotation performs the eviction.
    }

    /**
     * Drops the cached permission sets of every user whose permissions derive from the
     * given role, including users holding a role that inherits from it.
     *
     * <p>Called after a permission is attached to or detached from a role. Eviction is
     * per user because the cache is keyed by user id, so the affected users are resolved
     * from the role's descendant closure first.
     *
     * @param roleCode the role whose permission set changed (e.g. {@code LECTURER})
     */
    @Transactional(readOnly = true)
    public void evictRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return;
        }
        List<Role> affectedRoles = toRoles(descendantRoleCodes(roleCode));
        if (affectedRoles.isEmpty()) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_USER_PERMISSIONS);
        if (cache == null) {
            return;
        }
        // Evict through the CacheManager, not evictUser(): a self-invocation would
        // bypass the Spring proxy and silently skip the @CacheEvict.
        for (Long userId : rolePermissionRepository.findUserIdsByRoleCodes(affectedRoles)) {
            cache.evict(userId);
        }
    }

    /**
     * Collects the given role plus every role that transitively inherits from it.
     *
     * <p>A change to LECTURER also affects HEAD and ADMIN, because those roles read
     * LECTURER's grants through {@code role_hierarchy}.
     *
     * @param roleCode the starting role code
     * @return the role code and all its descendants
     */
    private Set<String> descendantRoleCodes(String roleCode) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(roleCode);
        while (!pending.isEmpty()) {
            String current = pending.pop();
            // Guards against a cycle if the hierarchy is ever mis-seeded.
            if (!visited.add(current)) {
                continue;
            }
            for (RoleHierarchy edge : roleHierarchyRepository.findByIdParentRoleCode(current)) {
                pending.push(edge.getChildRoleCode());
            }
        }
        return visited;
    }

    /**
     * Maps role codes to {@link Role} values, skipping codes with no enum constant.
     *
     * @param roleCodes raw role codes read from {@code role_hierarchy}
     * @return the resolvable roles
     */
    private static List<Role> toRoles(Set<String> roleCodes) {
        List<Role> roles = new ArrayList<>();
        for (String code : roleCodes) {
            try {
                roles.add(Role.valueOf(code));
            } catch (IllegalArgumentException ex) {
                // A role row without a Java enum constant has no users to evict.
            }
        }
        return roles;
    }
}
