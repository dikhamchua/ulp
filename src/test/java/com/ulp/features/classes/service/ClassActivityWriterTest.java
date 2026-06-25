package com.ulp.features.classes.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.ClassActivity;
import com.ulp.features.classes.repository.ClassActivityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link ClassActivityWriter}. Verifies the JSON serialization
 * happy path, the Jackson-failure â†’ null fallback, and the
 * {@code metadata = null} short-circuit.
 */
class ClassActivityWriterTest {

    private ClassActivityRepository repository;
    private ObjectMapper objectMapper;
    private ClassActivityWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(ClassActivityRepository.class);
        objectMapper = new ObjectMapper();
        writer = new ClassActivityWriter(repository, objectMapper);
    }

    @Test
    void write_without_metadata_persists_row_with_null_metadata() {
        writer.write(1L, ClassActivity.TYPE_CREATED, "Hello", 7L);

        ArgumentCaptor<ClassActivity> captor = ArgumentCaptor.forClass(ClassActivity.class);
        verify(repository).save(captor.capture());
        ClassActivity saved = captor.getValue();
        assertThat(saved.getClassId()).isEqualTo(1L);
        assertThat(saved.getType()).isEqualTo(ClassActivity.TYPE_CREATED);
        assertThat(saved.getDescription()).isEqualTo("Hello");
        assertThat(saved.getMetadata()).isNull();
        assertThat(saved.getCreatedBy()).isEqualTo(7L);
    }

    @Test
    void write_with_metadata_serializes_to_json() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("user_id", 42L);
        payload.put("note", "value with \"quotes\" and \\ slash");

        writer.write(1L, ClassActivity.TYPE_MEMBER_JOINED, "Joined", payload, 42L);

        ArgumentCaptor<ClassActivity> captor = ArgumentCaptor.forClass(ClassActivity.class);
        verify(repository).save(captor.capture());
        ClassActivity saved = captor.getValue();
        assertThat(saved.getMetadata())
                .contains("\"user_id\":42")
                .contains("\\\"quotes\\\"")
                .contains("\\\\ slash");
    }

    @Test
    void write_falls_back_to_null_when_serialization_fails() throws JsonProcessingException {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});

        ClassActivityWriter failingWriter = new ClassActivityWriter(repository, failing);
        failingWriter.write(1L, ClassActivity.TYPE_UPDATED, "x", Map.of("k", "v"), 7L);

        ArgumentCaptor<ClassActivity> captor = ArgumentCaptor.forClass(ClassActivity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMetadata()).isNull();
    }
}
