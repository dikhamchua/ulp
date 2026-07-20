package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * JPA entity mapping {@code library_assets} (personal lecturer file library).
 *
 * <p>Soft-deleted rows are filtered by {@link SQLRestriction}. Kind is either
 * {@link #KIND_DOCUMENT} or {@link #KIND_VIDEO}.
 */
@Entity
@Table(name = "library_assets")
@SQLRestriction("is_deleted = 0")
public class LibraryAsset {

    public static final String KIND_DOCUMENT = "DOCUMENT";
    public static final String KIND_VIDEO = "VIDEO";

    private static final Set<String> VALID_KINDS = Set.of(KIND_DOCUMENT, KIND_VIDEO);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_path", nullable = false, length = 500)
    private String storedPath;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected LibraryAsset() {
    }

    /**
     * Creates a new library asset ready to persist.
     *
     * @param ownerId          owning user id
     * @param title            display title (defaults to original filename at service)
     * @param originalFilename client filename (metadata only)
     * @param storedPath       relative path under the upload root
     * @param mimeType         resolved MIME type
     * @param sizeBytes        file size in bytes
     * @param kind             {@link #KIND_DOCUMENT} or {@link #KIND_VIDEO}
     */
    public LibraryAsset(Long ownerId, String title, String originalFilename,
                        String storedPath, String mimeType, long sizeBytes, String kind) {
        validateKind(kind);
        this.ownerId = ownerId;
        this.title = title;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.kind = kind;
        this.deleted = false;
    }

    @PrePersist
    void onPersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Renames the display title only — disk path stays unchanged. */
    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** Soft-deletes the row so default queries exclude it. */
    public void markDeleted() {
        this.deleted = true;
    }

    /** Whitelist guard for kind values. */
    public static void validateKind(String value) {
        if (value == null || !VALID_KINDS.contains(value)) {
            throw new IllegalArgumentException("Unknown library asset kind: " + value);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
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

    public String getKind() {
        return kind;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
