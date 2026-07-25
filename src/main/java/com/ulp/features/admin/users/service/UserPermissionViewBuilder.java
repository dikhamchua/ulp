package com.ulp.features.admin.users.service;

import com.ulp.entities.Permission;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.repository.EffectivePermissionRepository;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRow;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.admin.permissions.service.AdminPermissionsGuard;
import com.ulp.features.admin.users.dto.UserPermissionDtos.PermissionGroup;
import com.ulp.features.admin.users.dto.UserPermissionDtos.PermissionRow;
import com.ulp.features.admin.users.dto.UserPermissionDtos.UserPermissionView;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ulp.features.admin.users.dto.UserPermissionDtos.SOURCE_FROM_ROLE;
import static com.ulp.features.admin.users.dto.UserPermissionDtos.SOURCE_GRANT;
import static com.ulp.features.admin.users.dto.UserPermissionDtos.SOURCE_NONE;
import static com.ulp.features.admin.users.dto.UserPermissionDtos.SOURCE_REVOKE;

/**
 * Assembles the per-user permission view shown on the user edit screen.
 *
 * <p>Combines three sources: the whole permission catalogue, the permissions the
 * user's role chain grants (inheritance already expanded), and the overrides that
 * currently apply. Precedence matches {@code PermissionResolver}:
 * {@code REVOKE > GRANT > FROM_ROLE}.
 *
 * <p>Expiry is judged here against the JVM clock rather than in SQL, because
 * {@code expires_at} holds a zoneless {@code LocalDateTime} written by the JVM.
 */
@Component
public class UserPermissionViewBuilder {

    private final PermissionRepository permissionRepository;
    private final EffectivePermissionRepository effectivePermissionRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final AdminPermissionsGuard guard;

    public UserPermissionViewBuilder(PermissionRepository permissionRepository,
                                     EffectivePermissionRepository effectivePermissionRepository,
                                     UserPermissionOverrideRepository overrideRepository,
                                     AdminPermissionsGuard guard) {
        this.permissionRepository = permissionRepository;
        this.effectivePermissionRepository = effectivePermissionRepository;
        this.overrideRepository = overrideRepository;
        this.guard = guard;
    }

    /**
     * Builds the grouped permission view for one user.
     *
     * @param userId   the user being inspected
     * @param roleCode the user's role code, used to freeze core ADMIN rows
     * @return the tab payload, with groups ordered by group code
     */
    public UserPermissionView build(Long userId, String roleCode) {
        Set<Long> roleGranted = new HashSet<>();
        for (RolePermissionRow row : effectivePermissionRepository.findRoleDerivedPermissions(userId)) {
            roleGranted.add(row.getPermissionId());
        }
        Map<Long, String> activeOverrides = loadActiveOverrides(userId);

        Map<String, List<PermissionRow>> byGroup = new LinkedHashMap<>();
        int totalGranted = 0;
        for (Permission permission : permissionRepository.findAllByOrderByPermissionGroupAscFeatureKeyAsc()) {
            PermissionRow row = toRow(permission, roleCode,
                    roleGranted.contains(permission.getId()),
                    activeOverrides.get(permission.getId()));
            if (row.effective()) {
                totalGranted++;
            }
            byGroup.computeIfAbsent(permission.getPermissionGroup(), g -> new ArrayList<>()).add(row);
        }

        List<PermissionGroup> groups = new ArrayList<>(byGroup.size());
        for (Map.Entry<String, List<PermissionRow>> entry : byGroup.entrySet()) {
            int granted = (int) entry.getValue().stream().filter(PermissionRow::effective).count();
            groups.add(new PermissionGroup(entry.getKey(), entry.getValue(), granted));
        }
        return new UserPermissionView(userId, roleCode, groups, totalGranted, activeOverrides.size());
    }

    /** Resolves one catalogue permission into a row, applying override precedence. */
    private PermissionRow toRow(Permission permission, String roleCode,
                                boolean fromRole, String overrideType) {
        String source;
        boolean effective;
        if (UserPermissionOverride.TYPE_REVOKE.equals(overrideType)) {
            source = SOURCE_REVOKE;
            effective = false;
        } else if (UserPermissionOverride.TYPE_GRANT.equals(overrideType)) {
            source = SOURCE_GRANT;
            effective = true;
        } else if (fromRole) {
            source = SOURCE_FROM_ROLE;
            effective = true;
        } else {
            source = SOURCE_NONE;
            effective = false;
        }
        // Core ADMIN rows stay frozen: revoking them would lock admins out of recovery.
        boolean locked = guard.isCoreAdminPermission(roleCode,
                permission.getPermissionGroup(), permission.getFeatureKey());
        return new PermissionRow(permission.getFeatureKey(), permission.getName(),
                permission.getDescription(), source, effective, fromRole, locked);
    }

    /** Maps permission id to override type for the overrides currently in effect. */
    private Map<Long, String> loadActiveOverrides(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, String> active = new HashMap<>();
        for (UserPermissionOverride o : overrideRepository.findByUserIdOrderByIdDesc(userId)) {
            if (o.isInEffect(now)) {
                active.put(o.getPermissionId(), o.getOverrideType());
            }
        }
        return active;
    }
}
