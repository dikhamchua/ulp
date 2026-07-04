package com.ulp.features.tests.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.TestActivity;
import com.ulp.features.tests.repository.TestActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Centralises writes to the {@code activity_tests} audit table for lecturer
 * exam authoring, mirroring {@code AdminUsersAuditWriter}. Append-only: one
 * row per call, metadata serialised via Jackson (degrades to {@code null} on
 * serialisation failure).
 */
@Component
public class TestActivityWriter {

    private static final Logger log = LoggerFactory.getLogger(TestActivityWriter.class);

    private final TestActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    public TestActivityWriter(TestActivityRepository activityRepository,
                              ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    /** Writes a single activity row with the supplied (already serialised) metadata payload. */
    public void write(Long testId, String type, String description,
                      String metadata, Long actorId) {
        activityRepository.save(new TestActivity(testId, type, description, metadata, actorId));
    }

    /** Serialises an arbitrary payload to JSON; returns {@code null} when serialisation fails. */
    public String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize test activity metadata", ex);
            return null;
        }
    }
}
