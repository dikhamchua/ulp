package com.ulp.features.admin.permissions.service;

import com.ulp.entities.Permission;
import com.ulp.entities.RolePermission;
import com.ulp.features.admin.permissions.dto.PermissionDtos.MatrixCell;
import com.ulp.features.admin.permissions.dto.PermissionDtos.MatrixGroup;
import com.ulp.features.admin.permissions.dto.PermissionDtos.MatrixRow;
import com.ulp.features.admin.permissions.dto.PermissionDtos.MatrixView;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.RolePermissionRepository;
import com.ulp.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Reads and edits the role x permission matrix.
 *
 * <p>The matrix shows DIRECT grants only. A role that merely inherits a permission
 * through {@code role_hierarchy} shows an unchecked cell, matching the storage model:
 * ticking that cell would add a redundant direct grant rather than change behaviour.
 *
 * <p>Every write goes through {@link AdminPermissionsGuard} first, then
 * {@link PermissionAuditWriter}, then role-scoped cache eviction. Rejected changes throw
 * out of the guard before anything is persisted or audited.
 */
@Service
public class PermissionMatrixService {

    private static final String MSG_UNKNOWN_PERMISSION = "Không tìm thấy quyền: ";
    private static final String MSG_UNKNOWN_ROLE = "Vai trò không hợp lệ: ";

    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AdminPermissionsGuard guard;
    private final PermissionAuditWriter auditWriter;
    private final PermissionResolver permissionResolver;

    public PermissionMatrixService(PermissionRepository permissionRepository,
                                   RolePermissionRepository rolePermissionRepository,
                                   AdminPermissionsGuard guard,
                                   PermissionAuditWriter auditWriter,
                                   PermissionResolver permissionResolver) {
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.guard = guard;
        this.auditWriter = auditWriter;
        this.permissionResolver = permissionResolver;
    }

    /**
     * Builds the whole matrix, grouped by permission group.
     *
     * @return the matrix view model; groups and rows are ordered as the catalogue is
     */
    @Transactional(readOnly = true)
    public MatrixView loadMatrix() {
        List<String> roleCodes = roleCodes();

        // One query per role instead of per cell: the matrix is ~130 rows x 4 roles.
        Map<String, Set<Long>> grantsByRole = new LinkedHashMap<>();
        for (String roleCode : roleCodes) {
            Set<Long> permissionIds = new HashSet<>();
            for (RolePermission rp : rolePermissionRepository.findByIdRoleCode(roleCode)) {
                permissionIds.add(rp.getPermissionId());
            }
            grantsByRole.put(roleCode, permissionIds);
        }

        Map<String, List<MatrixRow>> rowsByGroup = new LinkedHashMap<>();
        for (Permission permission : permissionRepository.findAllByOrderByPermissionGroupAscFeatureKeyAsc()) {
            List<MatrixCell> cells = new ArrayList<>(roleCodes.size());
            for (String roleCode : roleCodes) {
                boolean granted = grantsByRole.get(roleCode).contains(permission.getId());
                boolean core = guard.isCoreAdminPermission(
                        roleCode, permission.getPermissionGroup(), permission.getFeatureKey());
                cells.add(new MatrixCell(roleCode, granted, core));
            }
            rowsByGroup.computeIfAbsent(permission.getPermissionGroup(), g -> new ArrayList<>())
                    .add(new MatrixRow(permission.getFeatureKey(), permission.getName(),
                            permission.getDescription(), cells));
        }

        List<MatrixGroup> groups = new ArrayList<>(rowsByGroup.size());
        rowsByGroup.forEach((group, rows) -> groups.add(new MatrixGroup(group, rows)));
        return new MatrixView(roleCodes, groups);
    }

    /**
     * Attaches a permission to a role, or does nothing when the grant already exists.
     *
     * @param roleCode   the role receiving the permission
     * @param featureKey the permission's feature key
     * @param actorId    the acting admin's user id
     */
    @Transactional
    public void attach(String roleCode, String featureKey, Long actorId) {
        Permission permission = requirePermission(featureKey);
        requireKnownRole(roleCode);
        if (rolePermissionRepository.existsByIdRoleCodeAndIdPermissionId(roleCode, permission.getId())) {
            return;
        }
        rolePermissionRepository.save(new RolePermission(roleCode, permission.getId()));
        auditWriter.writeMatrixChange(roleCode, featureKey, true, actorId);
        permissionResolver.evictRole(roleCode);
    }

    /**
     * Detaches a permission from a role unless the guard forbids it.
     *
     * @param roleCode   the role losing the permission
     * @param featureKey the permission's feature key
     * @param actorId    the acting admin's user id
     * @throws org.springframework.security.access.AccessDeniedException when the pair is
     *         a core ADMIN permission — the row is left untouched and nothing is audited
     */
    @Transactional
    public void detach(String roleCode, String featureKey, Long actorId) {
        Permission permission = requirePermission(featureKey);
        requireKnownRole(roleCode);
        // Guard first: a rejected detach must leave the grant row and write no audit.
        guard.checkDetachAllowed(roleCode, permission);
        var existing = rolePermissionRepository
                .findByIdRoleCodeAndIdPermissionId(roleCode, permission.getId());
        if (existing.isEmpty()) {
            return;
        }
        rolePermissionRepository.delete(existing.get());
        auditWriter.writeMatrixChange(roleCode, featureKey, false, actorId);
        permissionResolver.evictRole(roleCode);
    }

    /** Role codes forming the matrix columns, in enum order. */
    private static List<String> roleCodes() {
        List<String> codes = new ArrayList<>(Role.values().length);
        for (Role role : Role.values()) {
            codes.add(role.name());
        }
        return codes;
    }

    /** Loads a permission by feature key or fails — the UI never submits unknown keys. */
    private Permission requirePermission(String featureKey) {
        return permissionRepository.findByFeatureKey(featureKey)
                .orElseThrow(() -> new NoSuchElementException(MSG_UNKNOWN_PERMISSION + featureKey));
    }

    /** Rejects role codes with no enum constant so no orphan grant row can be created. */
    private static void requireKnownRole(String roleCode) {
        try {
            Role.valueOf(roleCode);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new NoSuchElementException(MSG_UNKNOWN_ROLE + roleCode);
        }
    }
}
