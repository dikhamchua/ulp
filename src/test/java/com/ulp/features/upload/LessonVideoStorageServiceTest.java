package com.ulp.features.upload;

import com.ulp.features.storage.LocalObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LessonVideoStorageService} against LocalObjectStorage.
 */
class LessonVideoStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalObjectStorage objectStorage;
    private LessonVideoStorageService service;

    @BeforeEach
    void setUp() {
        objectStorage = new LocalObjectStorage(tempDir);
        service = new LessonVideoStorageService(objectStorage);
    }

    private static byte[] mp4Bytes() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x20,
                0x66, 0x74, 0x79, 0x70,
                'i', 's', 'o', 'm',
                0x00, 0x00, 0x02, 0x00
        };
    }

    @Test
    void store_happy_path_returns_relative_path_and_writes_file() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "intro.mp4", "video/mp4", mp4Bytes());

        LessonVideoStorageService.StoredVideo stored = service.store(file, 42L);

        assertThat(stored.storedPath()).startsWith("lessons/42/video/").endsWith(".mp4");
        assertThat(stored.sizeBytes()).isEqualTo(mp4Bytes().length);
        assertThat(objectStorage.exists(stored.storedPath())).isTrue();
    }

    @Test
    void store_rejects_non_mp4_mime_even_when_extension_matches() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "intro.mp4", "video/quicktime", mp4Bytes());

        assertThatThrownBy(() -> service.store(file, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MP4");
    }

    @Test
    void store_rejects_oversize_file() {
        byte[] huge = new byte[(int) (200L * 1024L * 1024L + 1)];
        huge[4] = 0x66; huge[5] = 0x74; huge[6] = 0x79; huge[7] = 0x70;
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.mp4", "video/mp4", huge);

        assertThatThrownBy(() -> service.store(file, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200MB");
    }

    @Test
    void store_rejects_file_without_mp4_magic_bytes() {
        byte[] spoof = new byte[16];
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.mp4", "video/mp4", spoof);

        assertThatThrownBy(() -> service.store(file, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_by_key_removes_stored_file() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.mp4", "video/mp4", mp4Bytes());
        LessonVideoStorageService.StoredVideo stored = service.store(file, 7L);
        assertThat(objectStorage.exists(stored.storedPath())).isTrue();

        service.delete(stored.storedPath());

        assertThat(objectStorage.exists(stored.storedPath())).isFalse();
    }

    @Test
    void require_safe_key_rejects_traversal() {
        assertThatThrownBy(() ->
                service.requireSafeKey("lessons/1/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
