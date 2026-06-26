package com.ulp.features.admin.users.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.UserActivity;
import com.ulp.features.admin.users.repository.UserActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Centralises writes to the {@code user_activities} audit table for the
 * admin user-management feature.
 *
 * <p>Pulled out of {@code AdminUsersService} as part of the C.2 structural
 * split so the three split services
 * ({@code AdminUsersReadService}, {@code AdminUsersWriteService},
 * {@code AdminUsersLifecycleService}) can share the same single insertion
 * point without depending on each other. Behaviour is identical to the
 * pre-split inlined {@code writeActivity} helper — it just persists one row
 * per call and serialises the metadata map via Jackson.
 *
 * <p>Failure to serialise the metadata map degrades to a {@code null}
 * payload + a warning log, mirroring the original semantics.
 */
@Component
class AdminUsersAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AdminUsersAuditWriter.class);

    private final UserActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    AdminUsersAuditWriter(UserActivityRepository activityRepository,
                          ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    /** Writes a single activity row with the supplied (already serialised) metadata payload. */
    void write(Long targetUserId, String type, String message,
               String metadata, Long actorId) {
        activityRepository.save(new UserActivity(targetUserId, type, message, metadata, actorId));
    }

    /** Serialises an arbitrary payload to JSON; returns {@code null} when serialisation fails. */
    String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize user activity metadata", ex);
            return null;
        }
    }
}
