package com.ulp.features.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ulp.common.IConstant.MSG_ATTACHMENT_EMPTY;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_EXT_NOT_ALLOWED;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_INVALID;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_MAGIC_FAIL;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;

/**
 * Filesystem storage for lesson attachments (ULP-4.0c).
 *
 * <p>Mirrors {@link AvatarStorageService}: extension whitelist +
 * magic-byte verification + UUID-renamed layout. The whitelist is
 * {@code pdf, docx, pptx, xlsx, zip}; size cap is 20MB. Modern Office
 * formats are ZIP containers so a single {@code PK\x03\x04} check covers
 * the four ZIP-derived extensions.
 *
 * <p>Files land under {@code <uploadRoot>/lessons/{lessonId}/<uuid>.<ext>}.
 * Original filenames are kept only as metadata at the database layer.
 */
@Service
public class LessonAttachmentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonAttachmentStorageService.class);

    /** Cap matches the global multipart limit bumped in V15 / application.properties. */
    public static final long MAX_SIZE = 20L * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "pptx", "xlsx", "zip");

    /** Resolved MIME type per allowed extension (returned to the JSON row). */
    private static final Map<String, String> MIME_BY_EXT = Map.of(
            "pdf",  "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "zip",  "application/zip"
    );

    private final Path uploadRoot;

    public LessonAttachmentStorageService(@Value("${app.upload.dir:uploads}") String dir) {
        this.uploadRoot = Paths.get(dir, "lessons").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create lesson attachments root: " + uploadRoot, e);
        }
    }

    /**
     * Stores the uploaded file under {@code lessons/{lessonId}/<uuid>.<ext>}.
     *
     * @param file     uploaded multipart payload
     * @param lessonId owning lesson id (forms the sub-directory)
     * @return the relative {@link StoredAttachment} (stored path + metadata)
     * @throws IllegalArgumentException with a Vietnamese-friendly message
     *                                  for any validation failure
     * @throws IOException              if the file cannot be written
     */
    public StoredAttachment store(MultipartFile file, Long lessonId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_EMPTY);
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_TOO_LARGE);
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String ext = extractLowercaseExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_EXT_NOT_ALLOWED);
        }
        if (!hasValidMagicBytes(file, ext)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_MAGIC_FAIL);
        }

        Path lessonDir = uploadRoot.resolve(String.valueOf(lessonId)).normalize();
        // Defense-in-depth: lessonId is server-supplied, but verify anyway.
        if (!lessonDir.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        Files.createDirectories(lessonDir);

        String filename = UUID.randomUUID() + "." + ext;
        Path dest = lessonDir.resolve(filename);
        file.transferTo(dest.toFile());

        String storedRelative = "lessons/" + lessonId + "/" + filename;
        String mime = MIME_BY_EXT.getOrDefault(ext, "application/octet-stream");
        return new StoredAttachment(storedRelative, mime, file.getSize(), originalName);
    }

    /**
     * Deletes a previously-stored file. No-op when the file is missing or the
     * path escapes the upload root — callers may invoke this defensively from
     * cascade paths.
     */
    public void delete(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) return;
        try {
            Path target = resolveAbsolutePath(storedRelativePath);
            Files.deleteIfExists(target);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to delete attachment file {}: {}", storedRelativePath, e.getMessage());
        }
    }

    /**
     * Resolves a stored relative path to an absolute {@link Path}, rejecting
     * any value that escapes the upload root (defense against tampered DB
     * rows).
     */
    public Path resolveAbsolutePath(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        // Strip leading "lessons/" so the resolution is anchored at uploadRoot.
        String trimmed = storedRelativePath.startsWith("lessons/")
                ? storedRelativePath.substring("lessons/".length())
                : storedRelativePath;
        Path resolved = uploadRoot.resolve(trimmed).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return resolved;
    }

    /** Returns the resolved MIME type for a whitelisted extension. */
    public String mimeFor(String extension) {
        return MIME_BY_EXT.getOrDefault(extension, "application/octet-stream");
    }

    private static String extractLowercaseExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Magic-byte check covering the five whitelisted formats:
     * PDF starts with {@code 25 50 44 46 2D} ("%PDF-"); the four ZIP-derived
     * formats start with {@code 50 4B 03 04} (PK\x03\x04).
     */
    private boolean hasValidMagicBytes(MultipartFile file, String ext) throws IOException {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(8);
        }
        if (header.length < 4) return false;

        if ("pdf".equals(ext)) {
            // %PDF-
            return header.length >= 5
                    && header[0] == (byte) 0x25
                    && header[1] == (byte) 0x50
                    && header[2] == (byte) 0x44
                    && header[3] == (byte) 0x46
                    && header[4] == (byte) 0x2D;
        }
        // ZIP family (docx/pptx/xlsx/zip): PK\x03\x04
        return header[0] == (byte) 0x50
                && header[1] == (byte) 0x4B
                && header[2] == (byte) 0x03
                && header[3] == (byte) 0x04;
    }

    /** Result of a successful {@link #store(MultipartFile, Long)} call. */
    public record StoredAttachment(String storedPath, String mimeType,
                                   long sizeBytes, String originalFilename) {
    }
}
