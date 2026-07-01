-- ULP — V17__public_view_tokens.sql
-- Short-lived tokens that grant anonymous view-only access to a single
-- lesson attachment. Used by MS Office Online Viewer (view.officeapps.live.com)
-- which requires a public URL to embed DOCX/PPTX/XLSX files.

CREATE TABLE public_view_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    attachment_id BIGINT NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pvt_token (token),
    INDEX idx_pvt_expires (expires_at),
    CONSTRAINT fk_pvt_attachment FOREIGN KEY (attachment_id)
        REFERENCES lesson_attachments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
