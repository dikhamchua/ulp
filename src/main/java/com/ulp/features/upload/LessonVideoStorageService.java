package com.ulp.features.upload;

import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Object storage for lesson VIDEO uploads.
 *
 * <p>Files land under {@code lessons/{lessonId}/video/{uuid}.mp4}.
 * Exactly one video per lesson — store replaces the previous key when known.
 */
@Service
public class LessonVideoStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonVideoStorageService.class);

    private static final String PATH_PREFIX = "lessons/";

    private final ObjectStorage objectStorage;

    public LessonVideoStorageService(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    /**
     * Stores the uploaded MP4 under {@code lessons/{lessonId}/video/&lt;uuid&gt;.mp4}.
     *
     * @return the stored relative path suitable for {@code lessons.video_url}
     */
    public StoredVideo store(MultipartFile file, Long lessonId) throws IOException {
        UploadFileHelper.validateMp4Video(file);

        // Best-effort cleanup of a previous video key is done by callers that
        // know the old video_url; we still stamp a unique uuid each upload.
        String filename = UploadFileHelper.newUuidFilename(UploadFileHelper.EXT_MP4);
        String key = PATH_PREFIX + lessonId + "/video/" + filename;
        StorageKeys.requireSafeKey(key);

        try (InputStream in = file.getInputStream()) {
            objectStorage.put(key, in, UploadFileHelper.MIME_MP4, file.getSize());
        }

        return new StoredVideo(key, file.getSize());
    }

    /**
     * Deletes a previously stored video key. Prefer this over directory listing
     * (R2 has no cheap list-by-prefix in this abstraction).
     */
    public void delete(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) return;
        try {
            objectStorage.delete(StorageKeys.requireSafeKey(storedRelativePath));
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to delete video {}: {}", storedRelativePath, e.getMessage());
        }
    }

    /**
     * @deprecated Prefer {@link #delete(String)} with the known video key.
     * Kept as a no-op for callers that used directory wipe; R2 has no list-dir.
     */
    @Deprecated
    public void deleteByLessonId(Long lessonId) {
        // Object storage has no directory listing; callers must pass the key.
        log.debug("deleteByLessonId({}) is a no-op under ObjectStorage — pass the key to delete()", lessonId);
    }

    /** Validates a stored relative key (rejects traversal). */
    public String requireSafeKey(String storedRelativePath) {
        return StorageKeys.requireSafeKey(storedRelativePath);
    }

    /** Result of a successful {@link #store(MultipartFile, Long)} call. */
    public record StoredVideo(String storedPath, long sizeBytes) {
    }
}
