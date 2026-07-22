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
 * Supplementary DOCUMENT attachment on a {@link LessonTemplate}.
 *
 * <p>Always library-backed — templates never own one-off disk paths.
 */
@Entity
@Table(name = "lesson_template_attachments")
public class LessonTemplateAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "library_asset_id", nullable = false)
    private Long libraryAssetId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA-only constructor. */
    protected LessonTemplateAttachment() {
    }

    /**
     * Creates a library-backed supplementary attachment row for a template.
     */
    public LessonTemplateAttachment(Long templateId, Long libraryAssetId,
                                    String originalFilename, String mimeType,
                                    long sizeBytes, int displayOrder) {
        this.templateId = templateId;
        this.libraryAssetId = libraryAssetId;
        this.originalFilename = originalFilename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.displayOrder = displayOrder;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public Long getLibraryAssetId() {
        return libraryAssetId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
