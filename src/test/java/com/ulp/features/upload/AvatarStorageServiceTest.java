package com.ulp.features.upload;

import com.ulp.features.storage.LocalObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AvatarStorageService} against {@link LocalObjectStorage}.
 */
class AvatarStorageServiceTest {

    @TempDir
    Path tempRoot;

    private AvatarStorageService service;

    @BeforeEach
    void setUp() {
        service = new AvatarStorageService(new LocalObjectStorage(tempRoot));
    }

    private static byte[] jpegBytes() {
        byte[] b = new byte[20];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF;
        return b;
    }

    private static byte[] pngBytes() {
        byte[] b = new byte[20];
        b[0] = (byte) 0x89; b[1] = 'P'; b[2] = 'N'; b[3] = 'G';
        return b;
    }

    private static byte[] webpBytes() {
        byte[] b = new byte[20];
        b[0] = 'R'; b[1] = 'I'; b[2] = 'F'; b[3] = 'F';
        b[8] = 'W'; b[9] = 'E'; b[10] = 'B'; b[11] = 'P';
        return b;
    }

    @Test
    void luuFileJpegHopLe_traVeUrl() throws Exception {
        var file = new MockMultipartFile("avatar", "a.jpg", "image/jpeg", jpegBytes());
        String url = service.store(file);
        assertNotNull(url);
        assertTrue(url.startsWith("/uploads/avatars/"));
        assertTrue(url.endsWith(".jpg"));
    }

    @Test
    void luuFilePngHopLe_traVeUrl() throws Exception {
        var file = new MockMultipartFile("avatar", "a.png", "image/png", pngBytes());
        String url = service.store(file);
        assertTrue(url.endsWith(".png"));
    }

    @Test
    void luuFileWebpHopLe_khongVanException_traVeUrl() {
        var file = new MockMultipartFile("avatar", "a.webp", "image/webp", webpBytes());
        String url = assertDoesNotThrow(() -> service.store(file));
        assertTrue(url.endsWith(".webp"));
    }

    @Test
    void tuChoiFileSaiContentType() {
        var file = new MockMultipartFile("avatar", "a.gif", "image/gif", new byte[]{1, 2, 3, 4});
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    @Test
    void tuChoiFileQuaLon() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF; big[1] = (byte) 0xD8; big[2] = (byte) 0xFF;
        var file = new MockMultipartFile("avatar", "big.jpg", "image/jpeg", big);
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    @Test
    void tuChoiFileDungContentTypeNhungNoiDungKhongPhaiAnh() {
        var file = new MockMultipartFile("avatar", "fake.jpg", "image/jpeg", new byte[]{0, 1, 2, 3, 4, 5});
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }
}
