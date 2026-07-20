package com.ulp.features.admin.departments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.DepartmentActivity;
import com.ulp.features.admin.departments.repository.DepartmentActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single insertion point for {@code department_activities} rows.
 */
@Component
class DepartmentAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(DepartmentAuditWriter.class);

    private final DepartmentActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    DepartmentAuditWriter(DepartmentActivityRepository activityRepository,
                          ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    /** Persists one activity row. */
    void write(Long departmentId, String type, String message,
               String metadata, Long actorId) {
        activityRepository.save(
                new DepartmentActivity(departmentId, type, message, metadata, actorId));
    }

    /** Serialises metadata map; returns null when serialisation fails. */
    String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize department activity metadata", ex);
            return null;
        }
    }
}
