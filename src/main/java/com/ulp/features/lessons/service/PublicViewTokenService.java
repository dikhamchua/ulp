package com.ulp.features.lessons.service;

import com.ulp.entities.LessonAttachment;
import com.ulp.entities.PublicViewToken;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.PublicViewTokenRepository;
import com.ulp.features.upload.LessonAttachmentStorageService;
import com.ulp.features.upload.LibraryStorageService;
import com.ulp.features.upload.UploadFileHelper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Creates and resolves short-lived tokens that grant anonymous
 * view-only access to a lesson attachment. Tokens are consumed by
 * MS Office Online Viewer which requires a public URL to embed files.
 */
@Service
public class PublicViewTokenService {

    private static final Logger log = LoggerFactory.getLogger(PublicViewTokenService.class);
    private static final int TOKEN_VALIDITY_HOURS = 1;

    private final PublicViewTokenRepository tokenRepository;
    private final LessonAttachmentRepository attachmentRepository;
    private final LessonAttachmentStorageService storage;
    private final LibraryStorageService libraryStorage;
    private final String appBaseUrl;

    public PublicViewTokenService(PublicViewTokenRepository tokenRepository,
                                   LessonAttachmentRepository attachmentRepository,
                                   LessonAttachmentStorageService storage,
                                   LibraryStorageService libraryStorage,
                                   @Value("${app.base-url:http://localhost:8080}") String appBaseUrl) {
        this.tokenRepository = tokenRepository;
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.libraryStorage = libraryStorage;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * Creates a token and returns the absolute public URL that MS Office
     * Viewer should embed. The token expires after
     * {@value #TOKEN_VALIDITY_HOURS} hour(s).
     */
    @Transactional
    public String createPublicViewUrl(Long attachmentId) {
        // Reuse an existing live token to avoid unbounded accumulation.
        return tokenRepository.findLiveTokenByAttachmentId(attachmentId, LocalDateTime.now())
                .map(tok -> appBaseUrl + "/public/view/" + tok.getToken())
                .orElseGet(() -> {
                    PublicViewToken tok = PublicViewToken.create(attachmentId, TOKEN_VALIDITY_HOURS);
                    tokenRepository.save(tok);
                    return appBaseUrl + "/public/view/" + tok.getToken();
                });
    }

    /**
     * Resolves a token to the attachment's file handle.
     *
     * @throws EntityNotFoundException if the token is invalid or expired
     */
    @Transactional(readOnly = true)
    public AttachmentHandle resolve(String tokenValue) {
        PublicViewToken tok = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new EntityNotFoundException("Invalid token"));
        if (tok.isExpired()) {
            tokenRepository.delete(tok);
            throw new EntityNotFoundException("Token expired");
        }
        LessonAttachment att = attachmentRepository.findById(tok.getAttachmentId())
                .orElseThrow(() -> new EntityNotFoundException("Attachment not found"));
        // Dual-root: library-backed rows live under uploads/library, not lessons/.
        Path absolute = att.isLibraryBacked()
                || UploadFileHelper.isLibraryStoredPath(att.getStoredPath())
                ? libraryStorage.resolveAbsolutePath(att.getStoredPath())
                : storage.resolveAbsolutePath(att.getStoredPath());
        return new AttachmentHandle(absolute, att.getOriginalFilename(),
                att.getMimeType(), att.getSizeBytes());
    }

    /** Deletes expired tokens. Called by the scheduled cleanup task. */
    @Transactional
    public int cleanupExpired() {
        return tokenRepository.deleteExpired(LocalDateTime.now());
    }

    /** Tuple returned by {@link #resolve} so the controller can stream the file. */
    public record AttachmentHandle(Path absolutePath, String originalFilename,
                                    String mimeType, long sizeBytes) {
    }
}
