package com.ulp.features.upload;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test cho {@link AvatarStorageService}.
 *
 * <p>Test nay khoa lai Bug #2: nhanh kiem tra WebP doc header[8..11] tren mang
 * 8 byte gay ArrayIndexOutOfBoundsException. Phai chap nhan WebP hop le va tu
 * choi file sai type/qua lon ma KHONG van exception runtime.
 */
class AvatarStorageServiceTest {

    private final AvatarStorageService service =
            new AvatarStorageService(System.getProperty("java.io.tmpdir") + "/ulp-test-uploads");

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
        // Bug #2 regression: nhanh WebP truoc day doc header[8..11] tren mang 8 byte -> AIOOBE
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
        // content-type noi la jpeg nhung magic bytes khong khop -> tu choi, khong van exception
        var file = new MockMultipartFile("avatar", "fake.jpg", "image/jpeg", new byte[]{0, 1, 2, 3, 4, 5});
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }
}
