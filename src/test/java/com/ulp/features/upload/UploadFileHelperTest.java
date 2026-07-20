package com.ulp.features.upload;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for shared upload validation helpers.
 */
class UploadFileHelperTest {

    @Test
    void isLibraryStoredPath_detectsPrefix() {
        assertTrue(UploadFileHelper.isLibraryStoredPath("library/1/a.pdf"));
        assertFalse(UploadFileHelper.isLibraryStoredPath("lessons/1/a.pdf"));
        assertFalse(UploadFileHelper.isLibraryStoredPath(null));
    }

    @Test
    void extractLowercaseExtension_andMime() {
        assertEquals("pdf", UploadFileHelper.extractLowercaseExtension("Slide.PDF"));
        assertEquals("application/pdf", UploadFileHelper.mimeForExtension("pdf"));
        assertEquals("", UploadFileHelper.extractLowercaseExtension("noext"));
    }

    @Test
    void validateDocument_acceptsPdfMagic() throws Exception {
        byte[] pdf = "%PDF-1.4\n%EOF\n".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.pdf", "application/pdf", pdf);
        assertEquals("pdf", UploadFileHelper.validateDocument(file));
    }

    @Test
    void validateDocument_rejectsBadExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "x.exe", "application/octet-stream", new byte[]{1, 2, 3, 4});
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileHelper.validateDocument(file));
    }

    @Test
    void validateMp4Video_acceptsFtypMagic() throws Exception {
        byte[] mp4 = new byte[12];
        mp4[4] = 0x66;
        mp4[5] = 0x74;
        mp4[6] = 0x79;
        mp4[7] = 0x70;
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", mp4);
        assertEquals("mp4", UploadFileHelper.validateMp4Video(file));
    }

    @Test
    void resolveUnderRoot_blocksTraversal() {
        Path root = Paths.get("uploads", "library").toAbsolutePath().normalize();
        assertThrows(IllegalArgumentException.class,
                () -> UploadFileHelper.resolveUnderRoot(
                        root, "library/../secret.txt", "library/"));
    }

    @Test
    void resolveUnderRoot_stripsPrefix() {
        Path root = Paths.get("uploads", "library").toAbsolutePath().normalize();
        Path resolved = UploadFileHelper.resolveUnderRoot(
                root, "library/9/a.pdf", "library/");
        assertTrue(resolved.startsWith(root));
        assertTrue(resolved.endsWith(Paths.get("9", "a.pdf")));
    }
}
