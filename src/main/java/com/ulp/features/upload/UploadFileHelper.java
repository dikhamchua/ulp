package com.ulp.features.upload;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ulp.common.IConstant.MAX_VIDEO_SIZE_BYTES;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_EMPTY;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_EXT_NOT_ALLOWED;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_INVALID;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_MAGIC_FAIL;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;
import static com.ulp.common.IConstant.MSG_VIDEO_FILE_NOT_MP4;
import static com.ulp.common.IConstant.MSG_VIDEO_FILE_TOO_LARGE;

/**
 * Shared upload validation and path utilities for lesson/library storage
 * services. Keeps magic-byte, extension, MIME, and traversal checks in one
 * place so the three storage roots do not drift.
 */
public final class UploadFileHelper {

    /** Document attachments and library DOCUMENT assets share a 20 MB cap. */
    public static final long MAX_DOCUMENT_SIZE_BYTES = 20L * 1024 * 1024;

    public static final String EXT_PDF = "pdf";
    public static final String EXT_MP4 = "mp4";
    public static final String MIME_PDF = "application/pdf";
    public static final String MIME_MP4 = "video/mp4";

    /** Relative-path prefix used by personal library blobs under uploads/. */
    public static final String LIBRARY_PATH_PREFIX = "library/";

    public static final Set<String> DOCUMENT_EXTENSIONS =
            Set.of("pdf", "docx", "pptx", "xlsx", "zip");

    private static final Map<String, String> MIME_BY_EXT = Map.of(
            "pdf", MIME_PDF,
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "zip", "application/zip",
            "mp4", MIME_MP4
    );

    private UploadFileHelper() {
    }

    /** True when the stored path points at the personal library root. */
    public static boolean isLibraryStoredPath(String storedRelativePath) {
        return storedRelativePath != null && storedRelativePath.startsWith(LIBRARY_PATH_PREFIX);
    }

    /**
     * Requires a non-empty multipart payload; returns the original client
     * filename (never blank).
     */
    public static String requireOriginalFilename(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_EMPTY);
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return originalName;
    }

    /** Lowercase extension without the leading dot; empty when missing. */
    public static String extractLowercaseExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** MIME for a known extension, or {@code application/octet-stream}. */
    public static String mimeForExtension(String extension) {
        if (extension == null) return "application/octet-stream";
        return MIME_BY_EXT.getOrDefault(extension.toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }

    /** {@code <uuid>.<ext>} filename used on disk. */
    public static String newUuidFilename(String extension) {
        return UUID.randomUUID() + "." + extension;
    }

    /**
     * Validates a document upload (pdf/docx/pptx/xlsx/zip): size, whitelist,
     * and magic bytes. Returns the lowercase extension.
     */
    public static String validateDocument(MultipartFile file) throws IOException {
        String originalName = requireOriginalFilename(file);
        if (file.getSize() > MAX_DOCUMENT_SIZE_BYTES) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_TOO_LARGE);
        }
        String ext = extractLowercaseExtension(originalName);
        if (!DOCUMENT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_EXT_NOT_ALLOWED);
        }
        if (!hasDocumentMagicBytes(file, ext)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_MAGIC_FAIL);
        }
        return ext;
    }

    /**
     * Validates an MP4 video upload: size, extension, optional client MIME,
     * and {@code ftyp} magic. Returns {@link #EXT_MP4}.
     */
    public static String validateMp4Video(MultipartFile file) throws IOException {
        String originalName = requireOriginalFilename(file);
        if (file.getSize() > MAX_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(MSG_VIDEO_FILE_TOO_LARGE);
        }
        String ext = extractLowercaseExtension(originalName);
        if (!EXT_MP4.equals(ext)) {
            throw new IllegalArgumentException(MSG_VIDEO_FILE_NOT_MP4);
        }
        String mime = file.getContentType();
        // Some browsers omit Content-Type; only reject when present and wrong.
        if (mime != null && !mime.isBlank() && !MIME_MP4.equalsIgnoreCase(mime)) {
            throw new IllegalArgumentException(MSG_VIDEO_FILE_NOT_MP4);
        }
        if (!hasMp4MagicBytes(file)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_MAGIC_FAIL);
        }
        return EXT_MP4;
    }

    /**
     * Resolves {@code storedRelativePath} under {@code uploadRoot}, stripping an
     * optional leading {@code stripPrefix} (e.g. {@code lessons/} or
     * {@code library/}). Rejects path traversal outside the root.
     */
    public static Path resolveUnderRoot(Path uploadRoot, String storedRelativePath,
                                        String stripPrefix) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String trimmed = storedRelativePath;
        if (stripPrefix != null && !stripPrefix.isEmpty()
                && storedRelativePath.startsWith(stripPrefix)) {
            trimmed = storedRelativePath.substring(stripPrefix.length());
        }
        Path resolved = uploadRoot.resolve(trimmed).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return resolved;
    }

    /**
     * Ensures {@code child} stays inside {@code root} after normalize — used
     * before creating owner/lesson subdirectories.
     */
    public static Path requireChildOf(Path root, Path child) {
        Path normalized = child.normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return normalized;
    }

    /**
     * PDF: {@code %PDF-}. ZIP-family (docx/pptx/xlsx/zip): {@code PK\x03\x04}.
     */
    public static boolean hasDocumentMagicBytes(MultipartFile file, String ext)
            throws IOException {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(8);
        }
        if (header.length < 4) return false;
        if (EXT_PDF.equals(ext)) {
            return header.length >= 5
                    && header[0] == (byte) 0x25
                    && header[1] == (byte) 0x50
                    && header[2] == (byte) 0x44
                    && header[3] == (byte) 0x46
                    && header[4] == (byte) 0x2D;
        }
        return header[0] == (byte) 0x50
                && header[1] == (byte) 0x4B
                && header[2] == (byte) 0x03
                && header[3] == (byte) 0x04;
    }

    /** MP4 {@code ftyp} box marker at offset 4. */
    public static boolean hasMp4MagicBytes(MultipartFile file) throws IOException {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length < 8) return false;
        return header[4] == (byte) 0x66
                && header[5] == (byte) 0x74
                && header[6] == (byte) 0x79
                && header[7] == (byte) 0x70;
    }
}
