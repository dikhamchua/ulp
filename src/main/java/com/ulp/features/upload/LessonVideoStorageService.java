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
import java.util.stream.Stream;

/**
 * Filesystem storage for lesson VIDEO uploads (add-lesson-content-types,
 * Sprint 3).
 *
 * <p>Separate from {@link LessonAttachmentStorageService} so the 200 MB
 * cap and {@code video/mp4} MIME requirement do not weaken the attachment
 * service's multi-format guarantee. Validation is shared via
 * {@link UploadFileHelper}.
 *
 * <p>Files land under {@code <uploadRoot>/lessons/{lessonId}/video/{uuid}.mp4}.
 */
@Service
public class LessonVideoStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonVideoStorageService.class);

    private static final String PATH_PREFIX = "lessons/";

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
     * @return the stored relative path suitable for {@code lessons.video_url}
     */
    public StoredVideo store(MultipartFile file, Long lessonId) throws IOException {
        UploadFileHelper.validateMp4Video(file);

        Path videoDir = UploadFileHelper.requireChildOf(
                uploadRoot,
                uploadRoot.resolve(String.valueOf(lessonId)).resolve("video"));
        // Exactly one video per lesson — replace keeps disk usage bounded.
        deleteByLessonId(lessonId);
        Files.createDirectories(videoDir);

        String filename = UploadFileHelper.newUuidFilename(UploadFileHelper.EXT_MP4);
        Path dest = videoDir.resolve(filename);
        file.transferTo(dest.toFile());

        String relative = PATH_PREFIX + lessonId + "/video/" + filename;
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
        return UploadFileHelper.resolveUnderRoot(uploadRoot, storedRelativePath, PATH_PREFIX);
    }

    /** Result of a successful {@link #store(MultipartFile, Long)} call. */
    public record StoredVideo(String storedPath, long sizeBytes) {
    }
}
