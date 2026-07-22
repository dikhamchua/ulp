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
import java.nio.file.StandardCopyOption;
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
 * Filesystem storage for personal library assets under
 * {@code uploads/library/{ownerId}/}.
 *
 * <p>Document and MP4 validation is shared via {@link UploadFileHelper}.
 */
@Service
public class LibraryStorageService {

    private static final Logger log = LoggerFactory.getLogger(LibraryStorageService.class);

    /** @deprecated use {@link UploadFileHelper#MAX_DOCUMENT_SIZE_BYTES} */
    public static final long MAX_DOCUMENT_SIZE = MAX_DOCUMENT_SIZE_BYTES;

    private final Path libraryRoot;

    public LibraryStorageService(@Value("${app.upload.dir:uploads}") String dir) {
        this.libraryRoot = Paths.get(dir, "library").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.libraryRoot);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create library upload root: " + libraryRoot, e);
        }
    }

    /**
     * Stores a document or video under {@code library/{ownerId}/{uuid}.ext}.
     *
     * @param kind {@code DOCUMENT} or {@code VIDEO}; when null/blank, inferred
     *             from the file extension
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

        Path ownerDir = UploadFileHelper.requireChildOf(
                libraryRoot, libraryRoot.resolve(String.valueOf(ownerId)));
        Files.createDirectories(ownerDir);

        String filename = UploadFileHelper.newUuidFilename(ext);
        Path dest = ownerDir.resolve(filename);
        file.transferTo(dest.toFile());

        String storedRelative = LIBRARY_PATH_PREFIX + ownerId + "/" + filename;
        return new StoredLibraryFile(
                storedRelative,
                UploadFileHelper.mimeForExtension(ext),
                file.getSize(),
                originalName,
                resolvedKind);
    }

    /**
     * Copies an existing on-disk file into {@code library/{ownerId}/} as a new
     * library blob. Used when promoting one-off lesson uploads into the personal
     * library so templates/clones never share lesson-scoped paths.
     *
     * @param sourceAbsolute absolute path of the source file (must exist)
     * @param ownerId        library owner
     * @param originalFilename client-facing filename (extension drives MIME/kind)
     * @param kind           DOCUMENT or VIDEO
     */
    public StoredLibraryFile copyFromPath(Path sourceAbsolute, Long ownerId,
                                          String originalFilename, String kind)
            throws IOException {
        if (sourceAbsolute == null || !Files.isRegularFile(sourceAbsolute)) {
            throw new IllegalArgumentException(MSG_ATTACHMENT_INVALID);
        }
        String name = originalFilename == null || originalFilename.isBlank()
                ? sourceAbsolute.getFileName().toString()
                : originalFilename;
        String ext = UploadFileHelper.extractLowercaseExtension(name);
        String resolvedKind = resolveKind(kind, ext);

        Path ownerDir = UploadFileHelper.requireChildOf(
                libraryRoot, libraryRoot.resolve(String.valueOf(ownerId)));
        Files.createDirectories(ownerDir);

        String filename = UploadFileHelper.newUuidFilename(ext);
        Path dest = ownerDir.resolve(filename);
        Files.copy(sourceAbsolute, dest, StandardCopyOption.REPLACE_EXISTING);

        String storedRelative = LIBRARY_PATH_PREFIX + ownerId + "/" + filename;
        long size = Files.size(dest);
        return new StoredLibraryFile(
                storedRelative,
                UploadFileHelper.mimeForExtension(ext),
                size,
                name,
                resolvedKind);
    }

    /** Deletes a previously stored library file; no-op on missing/invalid paths. */
    public void delete(String storedRelativePath) {
        if (storedRelativePath == null || storedRelativePath.isBlank()) return;
        try {
            Path target = resolveAbsolutePath(storedRelativePath);
            Files.deleteIfExists(target);
        } catch (IllegalArgumentException | IOException e) {
            log.warn("Failed to delete library file {}: {}", storedRelativePath, e.getMessage());
        }
    }

    /**
     * Resolves a stored relative path under the library root with path-traversal
     * protection. Accepts paths starting with {@code library/} or already trimmed.
     */
    public Path resolveAbsolutePath(String storedRelativePath) {
        return UploadFileHelper.resolveUnderRoot(
                libraryRoot, storedRelativePath, LIBRARY_PATH_PREFIX);
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

    /** Result of a successful {@link #store} call. */
    public record StoredLibraryFile(String storedPath, String mimeType, long sizeBytes,
                                    String originalFilename, String kind) {
    }
}
