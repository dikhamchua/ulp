package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Short-lived token that grants anonymous view-only access to a single
 * lesson attachment. Consumed by MS Office Online Viewer which requires
 * a public URL to embed DOCX/PPTX/XLSX files.
 */
@Entity
@Table(name = "public_view_tokens")
public class PublicViewToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PublicViewToken() {
    }

    public PublicViewToken(Long attachmentId, String token, LocalDateTime expiresAt) {
        this.attachmentId = attachmentId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    /** Creates a token valid for {@code hoursValid} hours from now. */
    public static PublicViewToken create(Long attachmentId, int hoursValid) {
        return new PublicViewToken(
                attachmentId,
                UUID.randomUUID().toString().replace("-", ""),
                LocalDateTime.now().plusHours(hoursValid));
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    // ── Getters ──────────────────────────────────────────────────────

    public Long getId() { return id; }

    public Long getAttachmentId() { return attachmentId; }

    public String getToken() { return token; }

    public LocalDateTime getExpiresAt() { return expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
