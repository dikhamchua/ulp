package com.ulp.features.admin.permissions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.PermissionActivity;
import com.ulp.features.admin.permissions.repository.PermissionActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single insertion point for {@code permission_activities} rows.
 *
 * <p>Mirrors {@code DepartmentAuditWriter}. Every accepted RBAC change records the acting
 * admin, the target and the direction of the change. Rejected changes produce no row:
 * the guard throws before any writer method is reached.
 */
@Component
class PermissionAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuditWriter.class);

    private final PermissionActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    PermissionAuditWriter(PermissionActivityRepository activityRepository,
                          ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records a role x permission matrix change.
     *
     * @param roleCode   the role that gained or lost the permission
     * @param featureKey the permission's feature key
     * @param attached   {@code true} for an attach, {@code false} for a detach
     * @param actorId    the acting admin's user id
     */
    void writeMatrixChange(String roleCode, String featureKey, boolean attached, Long actorId) {
        String type = attached
                ? PermissionActivity.TYPE_ROLE_PERMISSION_ATTACHED
                : PermissionActivity.TYPE_ROLE_PERMISSION_DETACHED;
        String message = (attached ? "Đã cấp quyền " : "Đã gỡ quyền ") + featureKey
                + (attached ? " cho vai trò " : " khỏi vai trò ") + roleCode;
        String metadata = serialize(Map.of(
                "roleCode", roleCode,
                "featureKey", featureKey,
                "direction", attached ? "ATTACH" : "DETACH"));
        activityRepository.save(new PermissionActivity(
                type, roleCode, featureKey, null, message, metadata, actorId));
    }

    /**
     * Records a user-level override write.
     *
     * @param type         one of the {@code TYPE_OVERRIDE_*} constants
     * @param targetUserId the user the override applies to
     * @param featureKey   the permission's feature key
     * @param overrideType {@code GRANT} or {@code REVOKE}; {@code null} on deactivation
     * @param reason       the admin-supplied justification
     * @param actorId      the acting admin's user id
     */
    void writeOverrideChange(String type, Long targetUserId, String featureKey,
                             String overrideType, String reason, Long actorId) {
        String message = describeOverride(type, featureKey, overrideType);
        // Map.of rejects null values, so build the payload with a null-tolerant map.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetUserId", targetUserId);
        payload.put("featureKey", featureKey);
        payload.put("overrideType", overrideType);
        payload.put("reason", reason);
        activityRepository.save(new PermissionActivity(
                type, null, featureKey, targetUserId, message, serialize(payload), actorId));
    }

    /** Builds the Vietnamese audit message for an override event. */
    private static String describeOverride(String type, String featureKey, String overrideType) {
        if (PermissionActivity.TYPE_OVERRIDE_DEACTIVATED.equals(type)) {
            return "Đã huỷ ngoại lệ quyền " + featureKey;
        }
        String verb = PermissionActivity.TYPE_OVERRIDE_REPLACED.equals(type)
                ? "Đã thay ngoại lệ quyền "
                : "Đã tạo ngoại lệ quyền ";
        return verb + featureKey + " (" + overrideType + ")";
    }

    /** Serialises metadata map; returns null when serialisation fails. */
    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize permission activity metadata", ex);
            return null;
        }
    }
}
