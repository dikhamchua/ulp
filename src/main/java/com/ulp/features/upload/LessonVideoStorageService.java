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
import java.util.UUID;
import java.util.stream.Stream;

import static com.ulp.common.IConstant.MAX_VIDEO_SIZE_BYTES;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_EMPTY;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_INVALID;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_MAGIC_FAIL;
import static com.ulp.common.IConstant.MSG_VIDEO_FILE_NOT_MP4;
import static com.ulp.common.IConstant.MSG_VIDEO_FILE_TOO_LARGE;

/**
 * Filesystem storage for lesson VIDEO uploads (add-lesson-content-types,
 * Sprint 3).
 *
 * <p>Separate from {@link LessonAttachmentStorageService} so the 200 MB
 * cap and {@code video/mp4} MIME requirement do not weaken the
 * attachment service's stricter 20 MB + multi-format guarantee — see
 * design D3.
 *
 * <p>Files land under {@code <uploadRoot>/lessons/{lessonId}/video/{uuid}.mp4}.
 * The video stream endpoint (later wired up by
 * {@code LessonVideoStreamController}) resolves the relative path through
 * {@link #resolveAbsolutePath(String)} so a tampered DB row cannot escape
 * the upload root.
 */
@Service
public class LessonVideoStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonVideoStorageService.class);

    private static final String VIDEO_MIME = "video/mp4";
    private static final String VIDEO_EXT = "mp4";

    private final Path uploadRoot;

    public LessonVideoStorageService(@Value("${app.upload.dir:uploads}") String dir) {
        this.uploadRoot = Paths.get(dir, "lessons").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create lesson video root: " + uploadRoot, e);
        }
    }

    /**
     * Stores the uploaded MP4 under {@code lessons/{lessonId}/video/<uuid>.mp4}.
     *
     * <p>Validates: non-empty, MIME = {@code video/mp4}, size &le; 200 MB,
     * and magic-bytes match the MP4 {@code ftyp} box at offset 4.
     *
     * @return the stored relative path (server-relative, suitable for
     *         persistence in {@code lessons.video_url})
     */
    public StoredVideo store(MultipartFile file, Long lessonId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_EMPTY);
        }
        if (file.getSize() > MAX_VIDEO_SIZE_BYTES) {
            throw new IllegalArgumentException(MSG_VIDEO_FILE_TOO_LARGE);
        }
        // Browsers report the MIME type so we still magic-check below.
        String mime = file.getContentType();
        if (mime == null || !VIDEO_MIME.equalsIgnoreCase(mime)) {
            throw new IllegalArgumentException(MSG_VIDEO_FILE_NOT_MP4);
        }
        if (!hasMp4MagicBytes(file)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_MAGIC_FAIL);
        }

        Path videoDir = uploadRoot.resolve(String.valueOf(lessonId)).resolve("video").normalize();
        if (!videoDir.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        // Wipe any previous MP4 for the same lesson — exactly one video per
        // lesson is supported, replacing the old file keeps disk usage bounded.
        deleteByLessonId(lessonId);
        Files.createDirectories(videoDir);

        String filename = UUID.randomUUID() + "." + VIDEO_EXT;
        Path dest = videoDir.resolve(filename);
        file.transferTo(dest.toFile());

        String relative = "lessons/" + lessonId + "/video/" + filename;
        return new StoredVideo(relative, file.getSize());
    }

    /**
     * Removes every MP4 stored under {@code lessons/{lessonId}/video/}.
     * Safe to call when the directory does not exist (no-op).
     */
    public void deleteByLessonId(Long lessonId) {
        Path videoDir = uploadRoot.resolve(String.valueOf(lessonId)).resolve("video").normalize();
        if (!videoDir.startsWith(uploadRoot) || !Files.exists(videoDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(videoDir)) {
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete video file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list video dir {}: {}", videoDir, e.getMessage());
        }
    }

    /**
     * Resolves a stored relative path to an absolute {@link Path}, rejecting
     * any value that escapes the upload root.
     */
    public Path resolveAbsolutePath(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String trimmed = storedRelativePath.startsWith("lessons/")
                ? storedRelativePath.substring("lessons/".length())
                : storedRelativePath;
        Path resolved = uploadRoot.resolve(trimmed).normalize();
        if (!resolved.startsWith(uploadRoot)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        return resolved;
    }

    /**
     * Verifies the MP4 magic bytes — the {@code ftyp} box at offset 4 of
     * every well-formed MP4 file. Defeats spoofed client MIME headers.
     */
    private boolean hasMp4MagicBytes(MultipartFile file) throws IOException {
        byte[] header;
        try (var in = file.getInputStream()) {
            header = in.readNBytes(12);
        }
        if (header.length < 8) return false;
        // bytes 4-7 should be "ftyp" (0x66 0x74 0x79 0x70).
        return header[4] == (byte) 0x66
                && header[5] == (byte) 0x74
                && header[6] == (byte) 0x79
                && header[7] == (byte) 0x70;
    }

    /** Result of a successful {@link #store(MultipartFile, Long)} call. */
    public record StoredVideo(String storedPath, long sizeBytes) {
    }
}
