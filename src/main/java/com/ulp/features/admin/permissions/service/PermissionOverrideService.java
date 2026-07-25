package com.ulp.features.admin.permissions.service;

import com.ulp.entities.Permission;
import com.ulp.entities.PermissionActivity;
import com.ulp.entities.User;
import com.ulp.entities.UserPermissionOverride;
import com.ulp.features.admin.permissions.dto.PermissionDtos.CatalogEntry;
import com.ulp.features.admin.permissions.dto.PermissionDtos.OverrideCandidate;
import com.ulp.features.admin.permissions.dto.PermissionDtos.OverrideForm;
import com.ulp.features.admin.permissions.dto.PermissionDtos.OverrideRow;
import com.ulp.features.admin.permissions.repository.PermissionRepository;
import com.ulp.features.admin.permissions.repository.UserPermissionOverrideRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Manages per-user permission overrides.
 *
 * <p>An override adjusts one user's permissions relative to their role. The table carries
 * a unique index on {@code (user_id, permission_id)}, so submitting an override for a pair
 * that already has a row updates that row in place instead of inserting a second one.
 *
 * <p>Overrides are never deleted. Switching one off deactivates it, leaving the row as
 * history — which is also why the list includes inactive and expired rows so the screen
 * can mark rather than hide them.
 */
@Service
public class PermissionOverrideService {

    static final String MSG_REASON_REQUIRED = "Lý do không được để trống";
    static final String MSG_INVALID_TYPE = "Loại ngoại lệ phải là GRANT hoặc REVOKE";
    private static final String MSG_UNKNOWN_PERMISSION = "Không tìm thấy quyền: ";
    private static final String MSG_UNKNOWN_USER = "Không tìm thấy người dùng: ";
    private static final String MSG_UNKNOWN_OVERRIDE = "Không tìm thấy ngoại lệ quyền";

    private final UserPermissionOverrideRepository overrideRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final PermissionAuditWriter auditWriter;
    private final PermissionResolver permissionResolver;

    public PermissionOverrideService(UserPermissionOverrideRepository overrideRepository,
                                     PermissionRepository permissionRepository,
                                     UserRepository userRepository,
                                     PermissionAuditWriter auditWriter,
                                     PermissionResolver permissionResolver) {
        this.overrideRepository = overrideRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.auditWriter = auditWriter;
        this.permissionResolver = permissionResolver;
    }

    /**
     * Lists every override, newest first, including inactive and expired ones.
     *
     * @return the override rows for the admin screen
     */
    @Transactional(readOnly = true)
    public List<OverrideRow> listOverrides() {
        List<UserPermissionOverride> overrides = overrideRepository.findAllByOrderByIdDesc();
        if (overrides.isEmpty()) {
            return List.of();
        }
        Map<Long, String> emailsByUserId = loadEmails(overrides);
        Map<Long, String> keysByPermissionId = loadFeatureKeys(overrides);

        LocalDateTime now = LocalDateTime.now();
        List<OverrideRow> rows = new ArrayList<>(overrides.size());
        for (UserPermissionOverride o : overrides) {
            boolean expired = o.getExpiresAt() != null && !o.getExpiresAt().isAfter(now);
            rows.add(new OverrideRow(
                    o.getId(),
                    o.getUserId(),
                    emailsByUserId.get(o.getUserId()),
                    keysByPermissionId.get(o.getPermissionId()),
                    o.getOverrideType(),
                    o.getReason(),
                    o.getExpiresAt(),
                    o.isActive(),
                    expired));
        }
        return rows;
    }

    /**
     * Lists the active users an override may target, for the create form's picker.
     *
     * @return active users across every role, ordered by name
     */
    @Transactional(readOnly = true)
    public List<OverrideCandidate> listCandidates() {
        return userRepository.findByRoleInAndActiveTrueOrderByFullNameAsc(List.of(Role.values()))
                .stream()
                .map(u -> new OverrideCandidate(u.getId(), u.getFullName(), u.getEmail(),
                        u.getRole() == null ? null : u.getRole().name()))
                .toList();
    }

    /**
     * Lists the permission catalogue for the create form's dropdown.
     *
     * @return every permission, ordered by group then feature key
     */
    @Transactional(readOnly = true)
    public List<CatalogEntry> listCatalog() {
        return permissionRepository.findAllByOrderByPermissionGroupAscFeatureKeyAsc()
                .stream()
                .map(p -> new CatalogEntry(p.getFeatureKey(), p.getName(), p.getPermissionGroup()))
                .toList();
    }

    /**
     * Creates an override, or replaces the existing one for the same user + permission.
     *
     * @param form    the submitted override values
     * @param actorId the acting admin's user id
     * @throws IllegalArgumentException when the type is not GRANT/REVOKE or the reason is blank
     */
    @Transactional
    public void createOrReplace(OverrideForm form, Long actorId) {
        validate(form);
        Permission permission = requirePermission(form.featureKey());
        requireUser(form.userId());

        Optional<UserPermissionOverride> existing =
                overrideRepository.findByUserIdAndPermissionId(form.userId(), permission.getId());

        String auditType;
        if (existing.isPresent()) {
            // Update in place — a delete-then-insert would risk violating idx_upo_user_perm.
            existing.get().replaceWith(form.overrideType(), form.reason(), actorId, form.expiresAt());
            auditType = PermissionActivity.TYPE_OVERRIDE_REPLACED;
        } else {
            overrideRepository.save(new UserPermissionOverride(
                    form.userId(), permission.getId(), form.overrideType(),
                    form.reason(), actorId, form.expiresAt()));
            auditType = PermissionActivity.TYPE_OVERRIDE_CREATED;
        }

        auditWriter.writeOverrideChange(auditType, form.userId(), form.featureKey(),
                form.overrideType(), form.reason(), actorId);
        permissionResolver.evictUser(form.userId());
    }

    /**
     * Switches an override off while keeping its row as history.
     *
     * @param overrideId the override row to deactivate
     * @param actorId    the acting admin's user id
     */
    @Transactional
    public void deactivate(Long overrideId, Long actorId) {
        UserPermissionOverride override = overrideRepository.findById(overrideId)
                .orElseThrow(() -> new NoSuchElementException(MSG_UNKNOWN_OVERRIDE));
        override.deactivate();

        String featureKey = permissionRepository.findById(override.getPermissionId())
                .map(Permission::getFeatureKey)
                .orElse(null);
        auditWriter.writeOverrideChange(PermissionActivity.TYPE_OVERRIDE_DEACTIVATED,
                override.getUserId(), featureKey, null, override.getReason(), actorId);
        permissionResolver.evictUser(override.getUserId());
    }

    /** Rejects a blank reason and any type outside {GRANT, REVOKE}. */
    private static void validate(OverrideForm form) {
        if (form.reason() == null || form.reason().isBlank()) {
            throw new IllegalArgumentException(MSG_REASON_REQUIRED);
        }
        if (!UserPermissionOverride.TYPE_GRANT.equals(form.overrideType())
                && !UserPermissionOverride.TYPE_REVOKE.equals(form.overrideType())) {
            throw new IllegalArgumentException(MSG_INVALID_TYPE);
        }
    }

    /** Batch-loads the emails shown next to each override row. */
    private Map<Long, String> loadEmails(List<UserPermissionOverride> overrides) {
        Set<Long> userIds = new HashSet<>();
        for (UserPermissionOverride o : overrides) {
            userIds.add(o.getUserId());
        }
        Map<Long, String> emails = new HashMap<>();
        for (User user : userRepository.findAllById(userIds)) {
            emails.put(user.getId(), user.getEmail());
        }
        return emails;
    }

    /** Batch-loads the feature keys behind each override's permission id. */
    private Map<Long, String> loadFeatureKeys(List<UserPermissionOverride> overrides) {
        Set<Long> permissionIds = new HashSet<>();
        for (UserPermissionOverride o : overrides) {
            permissionIds.add(o.getPermissionId());
        }
        Map<Long, String> keys = new HashMap<>();
        for (Permission permission : permissionRepository.findAllById(permissionIds)) {
            keys.put(permission.getId(), permission.getFeatureKey());
        }
        return keys;
    }

    /** Loads a permission by feature key or fails — the UI never submits unknown keys. */
    private Permission requirePermission(String featureKey) {
        return permissionRepository.findByFeatureKey(featureKey)
                .orElseThrow(() -> new NoSuchElementException(MSG_UNKNOWN_PERMISSION + featureKey));
    }

    /** Ensures the override targets a real user before a row is written. */
    private void requireUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new NoSuchElementException(MSG_UNKNOWN_USER + userId);
        }
    }
}
