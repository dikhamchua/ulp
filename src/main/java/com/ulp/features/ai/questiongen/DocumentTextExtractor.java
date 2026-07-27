package com.ulp.features.ai.questiongen;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Turns an uploaded lecture document into the plain text sent to the model.
 *
 * <p>Supports PDF and DOCX. Format is decided by magic bytes, never by the file name — a
 * caller can rename anything to {@code .pdf}, and parsing an unexpected container is the
 * expensive failure we want to avoid.
 *
 * <p>All guards run before parsing so a malicious or merely oversized upload is rejected
 * without burning CPU on decompression or tokens on the provider.
 */
@Component
public class DocumentTextExtractor {

    /** Upper bound on an accepted upload. Larger files are refused unparsed. */
    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /**
     * Upper bound on the text handed to the model.
     *
     * <p>Roughly 8k tokens of Vietnamese prose — enough context to write questions from,
     * while keeping one generation inside a predictable cost. Longer documents are cut,
     * not rejected: the opening pages of lecture notes carry the material.
     */
    static final int MAX_TEXT_CHARS = 30_000;

    private static final byte[] MAGIC_PDF = {'%', 'P', 'D', 'F'};
    private static final byte[] MAGIC_ZIP = {(byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04};

    private static final String MSG_EMPTY_FILE = "Vui lòng chọn file tài liệu hoặc dán nội dung văn bản";
    private static final String MSG_TOO_LARGE = "File vượt quá kích thước tối đa 10 MB";
    private static final String MSG_UNREADABLE = "Không đọc được file đã tải lên";
    private static final String MSG_BAD_FORMAT = "Chỉ hỗ trợ file PDF hoặc DOCX";
    private static final String MSG_NO_TEXT = "Không trích được nội dung văn bản từ file";

    static {
        // Same hardening the Excel importer applies: bound the decompression a crafted
        // OOXML package can force before POI gives up.
        ZipSecureFile.setMinInflateRatio(0.005);
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    /**
     * Extracts plain text from an uploaded PDF or DOCX.
     *
     * @param file the upload; must be non-empty and within {@link #MAX_FILE_BYTES}
     * @return the document text, trimmed and capped at {@link #MAX_TEXT_CHARS}
     * @throws IllegalArgumentException when the file is missing, too large, not a
     *                                  supported format, or carries no text layer
     */
    public String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(MSG_EMPTY_FILE);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(MSG_TOO_LARGE);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException(MSG_UNREADABLE);
        }
        // Re-check after reading: getSize() reports the declared length, which a
        // streaming upload may under-report relative to the bytes actually delivered.
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(MSG_TOO_LARGE);
        }

        String text;
        if (startsWith(bytes, MAGIC_PDF)) {
            text = extractPdf(bytes);
        } else if (startsWith(bytes, MAGIC_ZIP)) {
            text = extractDocx(bytes);
        } else {
            throw new IllegalArgumentException(MSG_BAD_FORMAT);
        }

        return requireText(text, MSG_NO_TEXT);
    }

    /**
     * Normalises pasted text the same way an extracted document is normalised.
     *
     * @param raw the text the lecturer pasted into the panel
     * @return the trimmed text, capped at {@link #MAX_TEXT_CHARS}
     * @throws IllegalArgumentException when the text is blank
     */
    public String normalizePastedText(String raw) {
        return requireText(raw, MSG_EMPTY_FILE);
    }

    // ─────────────────────────────────────────────────────────────────

    /** Reads every page of a PDF through PDFBox's text stripper. */
    private static String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException | RuntimeException ex) {
            // A password-protected or structurally broken PDF lands here; the lecturer
            // only needs to know the file cannot be used, not why PDFBox gave up.
            throw new IllegalArgumentException(MSG_NO_TEXT);
        }
    }

    /** Reads the body text of a DOCX through POI's word extractor. */
    private static String extractDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException | RuntimeException ex) {
            // A ZIP that is not a Word package (e.g. an .xlsx renamed to .docx) fails here.
            throw new IllegalArgumentException(MSG_BAD_FORMAT);
        }
    }

    /**
     * Trims, rejects an empty result, and caps the length.
     *
     * <p>The empty-case message is supplied by the caller: "no text layer" is the right
     * thing to tell a lecturer whose PDF is a scan, but the wrong thing to tell one who
     * submitted a blank textarea.
     */
    private static String requireText(String raw, String emptyMessage) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return trimmed.length() > MAX_TEXT_CHARS ? trimmed.substring(0, MAX_TEXT_CHARS) : trimmed;
    }

    /** True when the buffer opens with the given signature. */
    private static boolean startsWith(byte[] bytes, byte[] magic) {
        return bytes.length >= magic.length
                && Arrays.equals(Arrays.copyOf(bytes, magic.length), magic);
    }
}
