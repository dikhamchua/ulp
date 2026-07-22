-- lecturer lesson templates — personal reusable lesson blueprints.
--
-- lesson_templates stores owner-scoped snapshots of lesson body metadata.
-- File payloads always live in library_assets (promote one-off uploads on save).
-- lesson_template_attachments holds supplementary DOCUMENT refs only.

CREATE TABLE lesson_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    content_type VARCHAR(20) NOT NULL,
    content_richtext LONGTEXT NULL,
    pdf_library_asset_id BIGINT NULL,
    video_provider VARCHAR(20) NULL,
    video_url VARCHAR(500) NULL,
    video_library_asset_id BIGINT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_lesson_templates_owner (owner_id),
    INDEX idx_lesson_templates_owner_deleted (owner_id, is_deleted),
    INDEX idx_lesson_templates_pdf_asset (pdf_library_asset_id),
    INDEX idx_lesson_templates_video_asset (video_library_asset_id),
    CONSTRAINT fk_lesson_templates_owner FOREIGN KEY (owner_id)
        REFERENCES users(id),
    CONSTRAINT fk_lesson_templates_pdf_asset FOREIGN KEY (pdf_library_asset_id)
        REFERENCES library_assets(id),
    CONSTRAINT fk_lesson_templates_video_asset FOREIGN KEY (video_library_asset_id)
        REFERENCES library_assets(id),
    CONSTRAINT chk_lesson_templates_content_type
        CHECK (content_type IN ('RICHTEXT', 'PDF', 'VIDEO')),
    CONSTRAINT chk_lesson_templates_content_shape CHECK (
        (content_type = 'RICHTEXT' AND content_richtext IS NOT NULL)
        OR (content_type = 'PDF' AND pdf_library_asset_id IS NOT NULL)
        OR (content_type = 'VIDEO'
            AND video_provider IS NOT NULL
            AND (
                video_library_asset_id IS NOT NULL
                OR (video_url IS NOT NULL AND video_url <> '')
            ))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE lesson_template_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    library_asset_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lta_template (template_id),
    INDEX idx_lta_library_asset (library_asset_id),
    CONSTRAINT fk_lta_template FOREIGN KEY (template_id)
        REFERENCES lesson_templates(id) ON DELETE CASCADE,
    CONSTRAINT fk_lta_library_asset FOREIGN KEY (library_asset_id)
        REFERENCES library_assets(id),
    CONSTRAINT chk_lta_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
