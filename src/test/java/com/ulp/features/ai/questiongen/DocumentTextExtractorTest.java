package com.ulp.features.ai.questiongen;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentTextExtractor}.
 *
 * <p>DOCX fixtures are built with POI rather than checked in as binaries, so the test
 * exercises a genuinely well-formed Word package without a fixture file to maintain.
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    /** Builds a real DOCX package containing one paragraph. */
    private static byte[] docxWith(String paragraph) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(paragraph);
            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("extract reads the body text of a real DOCX package")
    void extractsDocxText() throws IOException {
        byte[] docx = docxWith("Chương 1: Cấu trúc dữ liệu và giải thuật");
        MockMultipartFile file = new MockMultipartFile("file", "bai-giang.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        String text = extractor.extract(file);

        assertTrue(text.contains("Cấu trúc dữ liệu"),
                "extracted text should carry the paragraph body, got: " + text);
    }

    @Test
    @DisplayName("normalizePastedText trims but otherwise preserves pasted material")
    void normalizesPastedText() {
        assertEquals("Nội dung bài giảng",
                extractor.normalizePastedText("   Nội dung bài giảng \n "));
    }

    @Test
    @DisplayName("normalizePastedText rejects blank material")
    void rejectsBlankPastedText() {
        assertThrows(IllegalArgumentException.class,
                () -> extractor.normalizePastedText("   \n  "));
        assertThrows(IllegalArgumentException.class,
                () -> extractor.normalizePastedText(null));
    }

    @Test
    @DisplayName("normalizePastedText caps very long material at the char limit")
    void capsLongPastedText() {
        String huge = "a".repeat(DocumentTextExtractor.MAX_TEXT_CHARS + 500);
        assertEquals(DocumentTextExtractor.MAX_TEXT_CHARS,
                extractor.normalizePastedText(huge).length());
    }

    @Test
    @DisplayName("extract rejects a file whose magic bytes are neither PDF nor DOCX")
    void rejectsUnsupportedMagicBytes() {
        // Named .pdf on purpose: the format decision must come from the bytes, not the name.
        MockMultipartFile file = new MockMultipartFile("file", "bai-giang.pdf",
                "application/pdf", "day khong phai la PDF".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("PDF") || ex.getMessage().contains("DOCX"),
                "message should name the supported formats, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("extract rejects a ZIP container that is not a Word package")
    void rejectsNonWordZip() {
        byte[] fakeZip = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile("file", "bai-giang.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", fakeZip);

        assertThrows(IllegalArgumentException.class, () -> extractor.extract(file));
    }

    @Test
    @DisplayName("extract rejects an empty upload")
    void rejectsEmptyUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf",
                "application/pdf", new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> extractor.extract(file));
    }

    @Test
    @DisplayName("extract rejects an upload larger than the 10 MB cap")
    void rejectsOversizedUpload() {
        byte[] oversized = new byte[(int) DocumentTextExtractor.MAX_FILE_BYTES + 1];
        oversized[0] = '%';
        oversized[1] = 'P';
        oversized[2] = 'D';
        oversized[3] = 'F';
        MockMultipartFile file = new MockMultipartFile("file", "huge.pdf",
                "application/pdf", oversized);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(file));
        assertTrue(ex.getMessage().contains("10 MB"),
                "message should name the size cap, got: " + ex.getMessage());
    }
}
