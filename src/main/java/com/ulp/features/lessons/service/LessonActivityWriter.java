package com.ulp.features.lessons.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.LessonActivity;
import com.ulp.features.lessons.repository.LessonActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Centralises writes to the {@code activity_lessons} audit table.
 *
 * <p>Mirrors the {@code SectionActivityWriter} design so every Lesson
 * mutation — create, update, publish, unpublish, reorder, delete — funnels
 * through the same code path. Metadata is serialised via Jackson so
 * JSON-special characters in titles or bodies don't corrupt the audit
 * payload.
 *
 * <p>Audit writes are best-effort with respect to JSON serialisation: if
 * Jackson cannot serialise the metadata map, the writer logs a warning and
 * persists the row with {@code metadata = null} rather than aborting the
 * surrounding business transaction. DB errors during {@code save} continue
 * to propagate so the caller's transaction rolls back appropriately.
 */
@Component
public class LessonActivityWriter {

    private static final Logger log = LoggerFactory.getLogger(LessonActivityWriter.class);

    private final LessonActivityRepository repository;
    private final ObjectMapper objectMapper;

    public LessonActivityWriter(LessonActivityRepository repository,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Writes a single activity row with no metadata payload. */
    public void write(Long lessonId, String type, String description, Long actorId) {
        write(lessonId, type, description, null, actorId);
    }

    /**
     * Writes a single activity row, serialising the supplied metadata map
     * to JSON.
     *
     * @param lessonId    id of the lesson the activity belongs to
     * @param type        activity type constant (see {@link LessonActivity})
     * @param description human-readable description (may contain user input)
     * @param metadata    arbitrary key/value payload, or {@code null}
     * @param actorId     id of the user that triggered the activity
     */
    public void write(Long lessonId, String type, String description,
                      Map<String, Object> metadata, Long actorId) {
        String serialised = serialise(metadata);
        repository.save(new LessonActivity(lessonId, type, description,
                serialised, actorId));
    }

    private String serialise(Map<String, Object> payload) {
        if (payload == null) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize lesson activity metadata; storing null instead", ex);
            return null;
        }
    }
}
