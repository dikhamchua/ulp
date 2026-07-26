package com.ulp.features.upload;

import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import static com.ulp.features.upload.UploadFileHelper.MAX_DOCUMENT_SIZE_BYTES;

/**
 * Object storage for lesson attachments (ULP-4.0c).
 *
 * <p>Validation is delegated to {@link UploadFileHelper}. Keys land under
 * {@code lessons/{lessonId}/&lt;uuid&gt;.&lt;ext&gt;}.
 */
@Service
public class LessonAttachmentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonAttachmentStorageService.class);

    /** Cap matches the global multipart limit bumped in V15 / application.properties. */
    public static final long MAX_SIZE = MAX_DOCUMENT_SIZE_BYTES;

    private static final String PATH_PREFIX = "lessons/";

    private final ObjectStorage objectStorage;

    public LessonAttachmentStorageService(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    /**
     * Stores the uploaded file under {@code lessons/{lessonId}/&lt;uuid&gt;.&lt;ext&gt;}.
     */
    public StoredAttachment store(MultipartFile file, Long lessonId) throws IOException {
        String originalName = UploadFileHelper.requireOriginalFilename(file);
        String ext = UploadFileHelper.validateDocument(file);

        String filename = UploadFileHelper.newUuidFilename(ext);
        String key = PATH_PREFIX + lessonId + "/" + filename;
        StorageKeys.requireSafeKey(key);

        try (InputStream in = file.getInputStream()) {
            objectStorage.put(key, in, UploadFileHelper.mimeForExtension(ext), file.getSize());
        }

        return new StoredAttachment(
                key,
                UploadFileHelper.mimeForExtension(ext),
                file.getSize(),
                originalName);
    }

    /**
     * Deletes a previously-stored object. No-op when missing or key is invalid.
     */
    public void delete(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) return;
        try {
            String key = StorageKeys.requireSafeKey(storedRelativePath);
            objectStorage.delete(key);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to delete attachment {}: {}", storedRelativePath, e.getMessage());
        }
    }

    /**
     * Validates a stored relative key (rejects traversal). Kept for callers that
     * previously resolved absolute paths — now returns the normalised key.
     */
    public String requireSafeKey(String storedRelativePath) {
        return StorageKeys.requireSafeKey(storedRelativePath);
    }

    /** Returns the resolved MIME type for a whitelisted extension. */
    public String mimeFor(String extension) {
        return UploadFileHelper.mimeForExtension(extension);
    }

    /** Result of a successful {@link #store(MultipartFile, Long)} call. */
    public record StoredAttachment(String storedPath, String mimeType,
                                   long sizeBytes, String originalFilename) {
    }
}
