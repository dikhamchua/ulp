package com.ulp.features.upload;

import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageKeys;
import com.ulp.features.storage.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static com.ulp.common.IConstant.MSG_ATTACHMENT_EXT_NOT_ALLOWED;
import static com.ulp.common.IConstant.MSG_ATTACHMENT_INVALID;
import static com.ulp.entities.LibraryAsset.KIND_DOCUMENT;
import static com.ulp.entities.LibraryAsset.KIND_VIDEO;
import static com.ulp.features.upload.UploadFileHelper.DOCUMENT_EXTENSIONS;
import static com.ulp.features.upload.UploadFileHelper.EXT_MP4;
import static com.ulp.features.upload.UploadFileHelper.LIBRARY_PATH_PREFIX;
import static com.ulp.features.upload.UploadFileHelper.MAX_DOCUMENT_SIZE_BYTES;

/**
 * Object storage for personal library assets under {@code library/{ownerId}/}.
 */
@Service
public class LibraryStorageService {

    private static final Logger log = LoggerFactory.getLogger(LibraryStorageService.class);

    /** @deprecated use {@link UploadFileHelper#MAX_DOCUMENT_SIZE_BYTES} */
    public static final long MAX_DOCUMENT_SIZE = MAX_DOCUMENT_SIZE_BYTES;

    private final ObjectStorage objectStorage;

    public LibraryStorageService(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    /**
     * Stores a document or video under {@code library/{ownerId}/{uuid}.ext}.
     */
    public StoredLibraryFile store(MultipartFile file, Long ownerId, String kind)
            throws IOException {
        String originalName = UploadFileHelper.requireOriginalFilename(file);
        String ext = UploadFileHelper.extractLowercaseExtension(originalName);
        String resolvedKind = resolveKind(kind, ext);

        if (KIND_DOCUMENT.equals(resolvedKind)) {
            UploadFileHelper.validateDocument(file);
        } else {
            UploadFileHelper.validateMp4Video(file);
        }

        String filename = UploadFileHelper.newUuidFilename(ext);
        String key = LIBRARY_PATH_PREFIX + ownerId + "/" + filename;
        StorageKeys.requireSafeKey(key);

        try (InputStream in = file.getInputStream()) {
            objectStorage.put(key, in, UploadFileHelper.mimeForExtension(ext), file.getSize());
        }

        return new StoredLibraryFile(
                key,
                UploadFileHelper.mimeForExtension(ext),
                file.getSize(),
                originalName,
                resolvedKind);
    }

    /**
     * Copies an existing storage object into {@code library/{ownerId}/} as a new
     * library blob (used when promoting one-off lesson uploads).
     */
    public StoredLibraryFile copyFromKey(String sourceKey, Long ownerId,
                                         String originalFilename, String kind)
            throws IOException {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String safeSource = StorageKeys.requireSafeKey(sourceKey);
        if (!objectStorage.exists(safeSource)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }

        String name = originalFilename == null || originalFilename.isBlank()
                ? leafName(safeSource)
                : originalFilename;
        String ext = UploadFileHelper.extractLowercaseExtension(name);
        String resolvedKind = resolveKind(kind, ext);

        String filename = UploadFileHelper.newUuidFilename(ext);
        String destKey = LIBRARY_PATH_PREFIX + ownerId + "/" + filename;
        StorageKeys.requireSafeKey(destKey);

        objectStorage.copy(safeSource, destKey);

        long size;
        try (StoredObject opened = objectStorage.open(destKey)) {
            size = opened.contentLength() >= 0 ? opened.contentLength() : 0L;
        }

        return new StoredLibraryFile(
                destKey,
                UploadFileHelper.mimeForExtension(ext),
                size,
                name,
                resolvedKind);
    }

    /** Deletes a previously stored library object; no-op on missing/invalid keys. */
    public void delete(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) return;
        try {
            objectStorage.delete(StorageKeys.requireSafeKey(storedRelativePath));
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to delete library file {}: {}", storedRelativePath, e.getMessage());
        }
    }

    /** Validates a stored relative key (rejects traversal). */
    public String requireSafeKey(String storedRelativePath) {
        return StorageKeys.requireSafeKey(storedRelativePath);
    }

    private static String resolveKind(String kind, String ext) {
        if (kind != null && !kind.isBlank()) {
            String upper = kind.trim().toUpperCase(Locale.ROOT);
            if (KIND_DOCUMENT.equals(upper) || KIND_VIDEO.equals(upper)) {
                return upper;
            }
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        if (EXT_MP4.equals(ext)) return KIND_VIDEO;
        if (DOCUMENT_EXTENSIONS.contains(ext)) return KIND_DOCUMENT;
        throw new IllegalArgumentException(MSG_ATTACHMENT_EXT_NOT_ALLOWED);
    }

    private static String leafName(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    /** Result of a successful {@link #store} call. */
    public record StoredLibraryFile(String storedPath, String mimeType, long sizeBytes,
                                    String originalFilename, String kind) {
    }
}
