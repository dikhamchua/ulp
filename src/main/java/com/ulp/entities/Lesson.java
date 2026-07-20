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
 * JPA entity mapping the {@code lessons} table.
 *
 * <p>A lesson carries one of three body types selected by
 * {@code content_type}: {@code content_richtext}, or a FK to
 * {@code lesson_attachments} ({@code pdf_attachment_id}), or
 * {@code video_url} + {@code video_provider}.
 *
 * <p>Soft-deleted rows are filtered by {@link SQLRestriction}.
 * {@code display_order} is nullable so soft-deleted rows can release
 * their slot — the unique key {@code uk_lesson_section_order} allows
 * multiple NULLs.
 */
@Entity
@Table(name = "lessons")
@SQLRestriction("is_deleted = 0")
public class Lesson {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    public static final String CONTENT_TYPE_RICHTEXT = "RICHTEXT";
    public static final String CONTENT_TYPE_PDF = "PDF";
    public static final String CONTENT_TYPE_VIDEO = "VIDEO";

    /** Allowed values for {@link #contentType}; mirrored by the DB CHECK in V16. */
    private static final Set<String> VALID_CONTENT_TYPES =
            Set.of(CONTENT_TYPE_RICHTEXT, CONTENT_TYPE_PDF, CONTENT_TYPE_VIDEO);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 20)
    private String status;

    /**
     * Position within the parent section, zero-based. Nullable so soft-deleted
     * rows can release their slot — see {@link #markDeleted()}.
     */
    @Column(name = "display_order")
    private Short displayOrder;

    @Column(name = "content_richtext", columnDefinition = "LONGTEXT")
    private String contentRichtext;

    /** RICHTEXT / PDF / VIDEO — guarded by V16 CHECK + {@link #VALID_CONTENT_TYPES}. */
    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    /** FK to {@code lesson_attachments(id)} when content_type = PDF; NULL otherwise. */
    @Column(name = "pdf_attachment_id")
    private Long pdfAttachmentId;

    /** External URL (YouTube/Vimeo) or server-relative MP4 path; NULL outside VIDEO. */
    @Column(name = "video_url", length = 500)
    private String videoUrl;

    /** YOUTUBE / VIMEO / UPLOAD — NULL outside VIDEO. */
    @Column(name = "video_provider", length = 20)
    private String videoProvider;

    /**
     * Optional FK to {@code library_assets} when the uploaded video is a
     * library reference. Null for one-off lesson MP4s and external providers.
     */
    @Column(name = "video_library_asset_id")
    private Long videoLibraryAssetId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA-only constructor; do not call from application code. */
    protected Lesson() {
    }

    /**
     * Creates a new draft lesson ready to be persisted. Defaults
     * {@code content_type} to RICHTEXT so the lecturer can save a body
     * straight away without an explicit type pick.
     *
     * @param sectionId    owning section id
     * @param title        display title (<=300 chars)
     * @param displayOrder zero-based position within the section
     * @param createdBy    id of the lecturer who created the row
     */
    public Lesson(Long sectionId, String title, Short displayOrder, Long createdBy) {
        this.sectionId = sectionId;
        this.title = title;
        this.displayOrder = displayOrder;
        this.createdBy = createdBy;
        this.status = STATUS_DRAFT;
        this.contentType = CONTENT_TYPE_RICHTEXT;
        // CHECK chk_lesson_content_shape requires content_richtext to be
        // non-null when content_type=RICHTEXT. Default to empty string so
        // a freshly-created lesson satisfies the constraint without forcing
        // the caller to populate the body before the first persist.
        this.contentRichtext = "";
        this.deleted = false;
    }

    // ── Lifecycle hooks ────────────────────────────────────────────────

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

    // ── Business helpers ───────────────────────────────────────────────

    /** Renames the lesson. Caller must have already validated the new title. */
    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** Replaces the rich-text body. Caller must pass already-sanitised HTML. */
    public void updateContent(String sanitisedHtml) {
        this.contentRichtext = sanitisedHtml;
    }

    /**
     * Transitions the lesson to {@link #STATUS_PUBLISHED} and stamps
     * {@code publishedAt} with the current time. Idempotent on the status
     * field but the timestamp is refreshed on each call.
     */
    public void publish() {
        this.status = STATUS_PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /** Reverts the lesson to {@link #STATUS_DRAFT} and clears {@code publishedAt}. */
    public void unpublish() {
        this.status = STATUS_DRAFT;
        this.publishedAt = null;
    }

    /** Re-positions the lesson within its parent section. */
    public void changeOrder(short newOrder) {
        this.displayOrder = newOrder;
    }

    /**
     * Marks the lesson as soft-deleted. The {@link SQLRestriction} on this
     * entity will then exclude the row from default queries.
     *
     * <p>Also clears {@code display_order} to NULL so the lesson's slot is
     * released — the unique key {@code uk_lesson_section_order} allows
     * multiple NULLs, which lets a brand new lesson take the freed position
     * without colliding with the deleted row.
     */
    public void markDeleted() {
        this.deleted = true;
        this.displayOrder = null;
    }

    // ── Content-type setters ──────────────────────────────────────────

    /** Sets the PDF attachment FK. The caller (service) ensures the row exists. */
    public void setPdfAttachmentId(Long pdfAttachmentId) {
        this.pdfAttachmentId = pdfAttachmentId;
    }

    /** Sets the external video URL or server-relative MP4 path. */
    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    /** Sets the video provider (YOUTUBE / VIMEO / UPLOAD). */
    public void setVideoProvider(String videoProvider) {
        this.videoProvider = videoProvider;
    }

    /** Sets the optional library asset FK for an uploaded library video. */
    public void setVideoLibraryAssetId(Long videoLibraryAssetId) {
        this.videoLibraryAssetId = videoLibraryAssetId;
    }

    /** True when the uploaded video is backed by a library asset. */
    public boolean hasLibraryVideo() {
        return videoLibraryAssetId != null;
    }

    /**
     * Switches the lesson's content type and nulls every column that no
     * longer belongs to the new type. Does NOT delete files — the service
     * orchestrates that around the call (see design D7).
     *
     * @param newType one of {@link #CONTENT_TYPE_RICHTEXT},
     *                {@link #CONTENT_TYPE_PDF}, {@link #CONTENT_TYPE_VIDEO}
     * @throws IllegalArgumentException if {@code newType} is not in the
     *                                  whitelist
     */
    public void switchContentTypeTo(String newType) {
        validateContentType(newType);
        this.contentType = newType;
        // Wipe fields that the new type does not carry so the DB CHECK
        // never sees stale data lingering from the previous type.
        if (!CONTENT_TYPE_RICHTEXT.equals(newType)) {
            this.contentRichtext = null;
        }
        if (!CONTENT_TYPE_PDF.equals(newType)) {
            this.pdfAttachmentId = null;
        }
        if (!CONTENT_TYPE_VIDEO.equals(newType)) {
            this.videoUrl = null;
            this.videoProvider = null;
            this.videoLibraryAssetId = null;
        }
    }

    /** Whitelist guard shared with the constructor / switch method. */
    public static void validateContentType(String value) {
        if (value == null || !VALID_CONTENT_TYPES.contains(value)) {
            throw new IllegalArgumentException("Unknown content type: " + value);
        }
    }

    // ── Getters ────────────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatus() {
        return status;
    }

    public Short getDisplayOrder() {
        return displayOrder;
    }

    public String getContentRichtext() {
        return contentRichtext;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getPdfAttachmentId() {
        return pdfAttachmentId;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public String getVideoProvider() {
        return videoProvider;
    }

    public Long getVideoLibraryAssetId() {
        return videoLibraryAssetId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
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
