package com.ulp.features.upload;

import com.ulp.features.upload.LessonAttachmentStorageService.StoredAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LessonAttachmentStorageService}.
 *
 * <p>Covers the design D4 magic-byte gate: PDF must start with %PDF-,
 * ZIP-family extensions must start with PK\x03\x04. Renamed-executable
 * payloads must be rejected before any disk write.
 */
class LessonAttachmentStorageServiceTest {

    @TempDir
    Path tempRoot;

    private LessonAttachmentStorageService service;

    @BeforeEach
    void setUp() {
        service = new LessonAttachmentStorageService(tempRoot.toString());
    }

    private static byte[] pdfBytes() {
        // %PDF- followed by some padding
        return new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37, 0x0A, 0x0A};
    }

    private static byte[] zipBytes() {
        // PK\x03\x04 (ZIP / Office formats)
        return new byte[]{0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00};
    }

    private static byte[] mzBytes() {
        // MZ = Windows PE executable signature
        return new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00};
    }

    @Test
    void storesPdfHappyPath() throws IOException {
        var file = new MockMultipartFile("file", "handout.pdf", "application/pdf", pdfBytes());
        StoredAttachment stored = service.store(file, 42L);

        assertNotNull(stored);
        assertEquals("handout.pdf", stored.originalFilename());
        assertEquals("application/pdf", stored.mimeType());
        assertTrue(stored.storedPath().startsWith("lessons/42/"));
        assertTrue(stored.storedPath().endsWith(".pdf"));

        Path absolute = service.resolveAbsolutePath(stored.storedPath());
        assertTrue(Files.exists(absolute));
    }

    @Test
    void storesDocxHappyPathWithZipMagic() throws IOException {
        var file = new MockMultipartFile("file", "slides.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                zipBytes());
        StoredAttachment stored = service.store(file, 7L);
        assertTrue(stored.storedPath().endsWith(".docx"));
    }

    @Test
    void rejectsExtensionNotInWhitelist() {
        var file = new MockMultipartFile("file", "notes.txt", "text/plain",
                new byte[]{'h', 'e', 'l', 'l', 'o'});
        assertThrows(IllegalArgumentException.class,
                () -> service.store(file, 1L));
    }

    @Test
    void rejectsFileLargerThanTwentyMb() {
        byte[] big = new byte[20 * 1024 * 1024 + 1];
        // Even with a legit PDF magic, the size guard runs first.
        big[0] = 0x25; big[1] = 0x50; big[2] = 0x44; big[3] = 0x46; big[4] = 0x2D;
        var file = new MockMultipartFile("file", "huge.pdf", "application/pdf", big);
        assertThrows(IllegalArgumentException.class,
                () -> service.store(file, 1L));
    }

    @Test
    void rejectsDisguisedExecutable() {
        // .pdf extension but MZ header — D7 threat model entry "disguised exe".
        var file = new MockMultipartFile("file", "payload.pdf", "application/pdf", mzBytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.store(file, 1L));
    }

    @Test
    void rejectsEmptyFile() {
        var file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThrows(IllegalArgumentException.class,
                () -> service.store(file, 1L));
    }

    @Test
    void deleteRemovesFile() throws IOException {
        var file = new MockMultipartFile("file", "a.pdf", "application/pdf", pdfBytes());
        StoredAttachment stored = service.store(file, 9L);
        Path absolute = service.resolveAbsolutePath(stored.storedPath());
        assertTrue(Files.exists(absolute));

        service.delete(stored.storedPath());
        assertFalse(Files.exists(absolute));
    }

    @Test
    void resolveAbsolutePathRejectsTraversal() {
        // A tampered stored_path attempting to escape the upload root must
        // throw — defense against malicious DB rows.
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveAbsolutePath("lessons/../../etc/passwd"));
    }
}
