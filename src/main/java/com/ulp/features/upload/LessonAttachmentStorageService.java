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

import static com.ulp.features.upload.UploadFileHelper.MAX_DOCUMENT_SIZE_BYTES;

/**
 * Filesystem storage for lesson attachments (ULP-4.0c).
 *
 * <p>Validation is delegated to {@link UploadFileHelper}. Files land under
 * {@code <uploadRoot>/lessons/{lessonId}/<uuid>.<ext>}.
 */
@Service
public class LessonAttachmentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LessonAttachmentStorageService.class);

    /** Cap matches the global multipart limit bumped in V15 / application.properties. */
    public static final long MAX_SIZE = MAX_DOCUMENT_SIZE_BYTES;

    private static final String PATH_PREFIX = "lessons/";

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
     * @throws IllegalArgumentException with a Vietnamese-friendly message
     *                                  for any validation failure
     */
    public StoredAttachment store(MultipartFile file, Long lessonId) throws IOException {
        String originalName = UploadFileHelper.requireOriginalFilename(file);
        String ext = UploadFileHelper.validateDocument(file);

        Path lessonDir = UploadFileHelper.requireChildOf(
                uploadRoot, uploadRoot.resolve(String.valueOf(lessonId)));
        Files.createDirectories(lessonDir);

        String filename = UploadFileHelper.newUuidFilename(ext);
        Path dest = lessonDir.resolve(filename);
        file.transferTo(dest.toFile());

        String storedRelative = PATH_PREFIX + lessonId + "/" + filename;
        return new StoredAttachment(
                storedRelative,
                UploadFileHelper.mimeForExtension(ext),
                file.getSize(),
                originalName);
    }

    /**
     * Deletes a previously-stored file. No-op when missing or path escapes
     * the upload root — callers may invoke this defensively from cascade paths.
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
     * any value that escapes the upload root.
     */
    public Path resolveAbsolutePath(String storedRelativePath) {
        return UploadFileHelper.resolveUnderRoot(uploadRoot, storedRelativePath, PATH_PREFIX);
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
