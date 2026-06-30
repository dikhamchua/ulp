package com.ulp.features.upload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LessonVideoStorageService}. Uses an isolated
 * {@link TempDir} as the upload root so tests do not bleed into the
 * shared {@code uploads/} folder.
 */
class LessonVideoStorageServiceTest {

    @TempDir
    Path tempDir;

    private LessonVideoStorageService service;

    @BeforeEach
    void setUp() {
        service = new LessonVideoStorageService(tempDir.toString());
    }

    /** Builds a minimal "MP4" header that passes the magic-byte check. */
    private static byte[] mp4Bytes() {
        // 4-byte size + 'ftyp' magic at offset 4, then padding so length > 8.
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
        Path absolute = service.resolveAbsolutePath(stored.storedPath());
        assertThat(Files.exists(absolute)).isTrue();
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
        // Build a >200MB array to exceed the cap. Real MP4 contents not
        // required because the size check runs before magic-byte check.
        byte[] huge = new byte[(int) (200L * 1024L * 1024L + 1)];
        // ftyp box at the right offset so we cannot accidentally fail on magic
        // bytes if the order ever shifts.
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
        // No ftyp at offset 4 — magic check must fail.
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.mp4", "video/mp4", spoof);

        assertThatThrownBy(() -> service.store(file, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_by_lesson_id_is_noop_when_no_video_exists() {
        // Directory does not exist yet — must not throw.
        service.deleteByLessonId(999L);
    }

    @Test
    void delete_by_lesson_id_removes_stored_file() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.mp4", "video/mp4", mp4Bytes());
        LessonVideoStorageService.StoredVideo stored = service.store(file, 7L);
        Path absolute = service.resolveAbsolutePath(stored.storedPath());
        assertThat(Files.exists(absolute)).isTrue();

        service.deleteByLessonId(7L);

        assertThat(Files.exists(absolute)).isFalse();
    }

    @Test
    void store_replaces_previous_video_for_same_lesson() throws Exception {
        MockMultipartFile first = new MockMultipartFile(
                "file", "a.mp4", "video/mp4", mp4Bytes());
        MockMultipartFile second = new MockMultipartFile(
                "file", "b.mp4", "video/mp4", mp4Bytes());

        LessonVideoStorageService.StoredVideo firstStored = service.store(first, 11L);
        LessonVideoStorageService.StoredVideo secondStored = service.store(second, 11L);

        // The first file is gone; only the second remains.
        assertThat(service.resolveAbsolutePath(firstStored.storedPath()))
                .doesNotExist();
        assertThat(service.resolveAbsolutePath(secondStored.storedPath()))
                .exists();
    }

    @Test
    void resolve_absolute_path_rejects_traversal() {
        assertThatThrownBy(() ->
                service.resolveAbsolutePath("lessons/1/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
