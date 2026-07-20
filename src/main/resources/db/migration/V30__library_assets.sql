-- lecturer-file-library — personal media library for lecturers.
--
-- New table library_assets (owner-scoped, soft-delete) plus nullable FKs so
-- lessons can reference library files without copying disk bytes:
--   * lesson_attachments.library_asset_id
--   * lessons.video_library_asset_id
--
-- See openspec/changes/lecturer-file-library/design.md (D1–D5).

CREATE TABLE library_assets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_path VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_library_assets_owner (owner_id),
    INDEX idx_library_assets_owner_kind (owner_id, kind),
    INDEX idx_library_assets_owner_deleted (owner_id, is_deleted),
    CONSTRAINT fk_library_assets_owner FOREIGN KEY (owner_id)
        REFERENCES users(id),
    CONSTRAINT chk_library_assets_kind CHECK (kind IN ('DOCUMENT', 'VIDEO')),
    CONSTRAINT chk_library_assets_size CHECK (size_bytes >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE lesson_attachments
    ADD COLUMN library_asset_id BIGINT NULL,
    ADD INDEX idx_la_library_asset (library_asset_id),
    ADD CONSTRAINT fk_la_library_asset FOREIGN KEY (library_asset_id)
        REFERENCES library_assets(id);

ALTER TABLE lessons
    ADD COLUMN video_library_asset_id BIGINT NULL,
    ADD INDEX idx_lessons_video_library_asset (video_library_asset_id),
    ADD CONSTRAINT fk_lesson_video_library_asset FOREIGN KEY (video_library_asset_id)
        REFERENCES library_assets(id);
