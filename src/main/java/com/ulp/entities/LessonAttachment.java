package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code lesson_attachments} table introduced by V15.
 *
 * <p>An attachment belongs to a single {@link Lesson} and is hard-deleted
 * together with its on-disk file when the lecturer removes it or when the
 * parent lesson is soft-deleted (see ULP-4.0c design D1/D2). There is no
 * {@code is_deleted} column — when this row is gone, the file is gone too.
 *
 * <p>Plain getters (no Lombok {@code @Data}) to avoid the equals/hashCode
 * pitfalls flagged in the project conventions.
 */
@Entity
@Table(name = "lesson_attachments")
public class LessonAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    /** JPA-only constructor; do not call from application code. */
    protected LessonAttachment() {
    }

    /**
     * Creates a new attachment row ready to persist.
     *
     * @param lessonId         owning lesson id
     * @param originalFilename filename as uploaded by the client (metadata only)
     * @param storedPath       relative path under the upload root
     * @param mimeType         resolved MIME type (from extension whitelist)
     * @param sizeBytes        file size in bytes
     * @param uploadedBy       id of the lecturer who uploaded the file
     */
    public LessonAttachment(Long lessonId, String originalFilename, String storedPath,
                            String mimeType, long sizeBytes, Long uploadedBy) {
        this.lessonId = lessonId;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
    }

    @PrePersist
    void onPersist() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getLessonId() {
        return lessonId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
}
