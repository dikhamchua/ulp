-- ULP-4.0b — Replace the lessons table with the slimmer schema for the
-- Lesson CRUD feature.
--
-- V1 created `lessons` as a child of `sections` with a multi-type
-- discriminator (RICH_TEXT / PDF / VIDEO) and a body stored in a sibling
-- `lesson_contents` table. ULP-4.0b narrows the scope to a single in-row
-- rich-text body and defers attachments to ULP-4.0c. The table is still
-- empty in every environment so we DROP + CREATE rather than ALTER —
-- this side-steps the auto-named MySQL CHECK constraint that the
-- original `status VARCHAR(20) CHECK (...)` baked in, which a sequence of
-- ALTERs cannot remove without first looking up the auto-generated name.
--
-- New schema (rationale per design D4):
--   * `display_order SMALLINT NULL` — nullable so a soft-deleted lesson
--     can clear its slot and let a new lesson reclaim that position
--     (MySQL unique indexes allow multiple NULLs); mirrors the V13
--     soft-delete-safe pattern from sections.
--   * `status VARCHAR(20)` with a named CHECK constraint
--     (`chk_lesson_status`) tightened to ('DRAFT','PUBLISHED'); ARCHIVED
--     is gone.
--   * `content_richtext LONGTEXT NULL` — inline sanitised HTML body
--     produced by Quill on the lesson form.
--   * Drops the legacy `type`, `estimated_minutes`, `sort_order` columns
--     — no more in-row type discriminator (attachments come in 4.0c) and
--     `estimated_minutes` was a nice-to-have without a user story.
--   * Adds `idx_lesson_section_id(section_id, is_deleted)` for the list
--     query plus a `uk_lesson_section_order(section_id, display_order)`
--     unique key so two live lessons cannot share a slot.
--
-- The sibling `lesson_contents` table is intentionally left untouched —
-- 4.0c revisits it for the real attachment path.

-- The audit table activity_lessons references lessons.id via fk_al_lesson;
-- disable FK checks for the duration of the drop so we don't have to
-- temporarily detach + reattach that constraint. Both tables are empty
-- in every environment today so no data integrity work is required.
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS lessons;
SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE lessons (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    section_id BIGINT NOT NULL,
    title VARCHAR(300) NOT NULL,
    display_order SMALLINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    content_richtext LONGTEXT NULL,
    created_by BIGINT NOT NULL,
    published_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    INDEX idx_lesson_section_id (section_id, is_deleted),
    INDEX idx_lesson_status (status),
    UNIQUE KEY uk_lesson_section_order (section_id, display_order),
    CONSTRAINT chk_lesson_status CHECK (status IN ('DRAFT','PUBLISHED')),
    CONSTRAINT fk_lesson_section FOREIGN KEY (section_id)
        REFERENCES sections(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_creator FOREIGN KEY (created_by)
        REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
