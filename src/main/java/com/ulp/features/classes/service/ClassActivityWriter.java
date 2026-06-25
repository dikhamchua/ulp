package com.ulp.features.classes.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.ClassActivity;
import com.ulp.features.classes.repository.ClassActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Centralizes writes to the {@code activity_classes} audit table.
 *
 * <p>Encapsulates the construction of {@link ClassActivity} rows so that every
 * caller (class CRUD, invite code regeneration, enrollment join/leave) shares a
 * single code path. Metadata maps are serialized via {@link ObjectMapper} —
 * never by manual string concatenation — to keep the audit JSON well-formed when
 * fields contain quotes, backslashes, or other JSON-special characters.
 *
 * <p>Audit writes are best-effort with respect to JSON serialization: if
 * Jackson cannot serialize the supplied metadata, the writer logs a warning
 * and persists the activity row with {@code metadata = null} rather than
 * aborting the surrounding business transaction. Database errors during
 * {@code repository.save} continue to propagate so the caller's transaction
 * rolls back appropriately.
 */
@Component
public class ClassActivityWriter {

    private static final Logger log = LoggerFactory.getLogger(ClassActivityWriter.class);

    private final ClassActivityRepository repository;
    private final ObjectMapper objectMapper;

    public ClassActivityWriter(ClassActivityRepository repository,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Writes a single activity row with no metadata payload.
     */
    public void write(Long classId, String type, String description, Long actorId) {
        write(classId, type, description, null, actorId);
    }

    /**
     * Writes a single activity row, serializing the supplied metadata map to JSON.
     *
     * @param classId     id of the class the activity belongs to
     * @param type        activity type constant (see {@link ClassActivity})
     * @param description human-readable description (may contain user-facing strings)
     * @param metadata    arbitrary key/value payload, or {@code null} if not needed
     * @param actorId     id of the user that triggered the activity
     */
    public void write(Long classId, String type, String description,
                       Map<String, Object> metadata, Long actorId) {
        String serialized = serialize(metadata);
        repository.save(new ClassActivity(classId, type, description, serialized, actorId));
    }

    private String serialize(Map<String, Object> payload) {
        if (payload == null) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize activity metadata; storing null instead", ex);
            return null;
        }
    }
}
