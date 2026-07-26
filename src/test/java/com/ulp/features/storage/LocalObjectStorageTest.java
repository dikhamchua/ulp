package com.ulp.features.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocalObjectStorage} covering CRUD, range, and traversal.
 */
class LocalObjectStorageTest {

    @TempDir
    Path tempRoot;

    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(tempRoot);
    }

    @Test
    void putOpenExistsDelete_roundTrip() throws Exception {
        byte[] data = "hello-ulp".getBytes(StandardCharsets.UTF_8);
        storage.put("avatars/a.txt", new ByteArrayInputStream(data), "text/plain", data.length);

        assertThat(storage.exists("avatars/a.txt")).isTrue();
        try (StoredObject obj = storage.open("avatars/a.txt")) {
            assertThat(obj.contentLength()).isEqualTo(data.length);
            assertThat(obj.inputStream().readAllBytes()).isEqualTo(data);
        }

        storage.delete("avatars/a.txt");
        assertThat(storage.exists("avatars/a.txt")).isFalse();
    }

    @Test
    void openRange_returnsPartialBytes() throws Exception {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        storage.put("lessons/1/v.bin", new ByteArrayInputStream(data), "application/octet-stream", data.length);

        try (StoredObject obj = storage.openRange("lessons/1/v.bin", 2, 5)) {
            assertThat(obj.contentLength()).isEqualTo(4);
            assertThat(obj.inputStream().readAllBytes())
                    .isEqualTo("2345".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void copy_duplicatesObject() throws Exception {
        byte[] data = "copy-me".getBytes(StandardCharsets.UTF_8);
        storage.put("library/1/src.pdf", new ByteArrayInputStream(data), "application/pdf", data.length);
        storage.copy("library/1/src.pdf", "library/1/dst.pdf");

        assertThat(storage.exists("library/1/dst.pdf")).isTrue();
        try (StoredObject obj = storage.open("library/1/dst.pdf")) {
            assertThat(obj.inputStream().readAllBytes()).isEqualTo(data);
        }
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() ->
                storage.put("../etc/passwd", new ByteArrayInputStream(new byte[]{1}), "text/plain", 1))
                .isInstanceOf(IllegalArgumentException.class);
        // exists() swallows invalid keys and reports false (no leak).
        assertThat(storage.exists("avatars/../../secret")).isFalse();
        assertThatThrownBy(() -> storage.open("lessons/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
