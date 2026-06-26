-- ULP-4.0a — Migrate the legacy sections table to the class-scoped layout
-- introduced for the lessons tab.
--
-- The V1 schema created `sections` as a child of `courses` (since dropped
-- in V7). With courses gone, sections must now hang directly off `classes`.
-- Both `sections` and `lessons` are still empty in every environment (0
-- rows in dev seed + nothing imported), so we ALTER the existing table
-- instead of dropping it — keeping the FK from `lessons.section_id` intact
-- for ULP-4.0b.
--
-- Changes:
--   * Drop legacy course_id FK + column.
--   * Add class_id (FK -> classes ON DELETE CASCADE).
--   * Replace `sort_order INT` with `display_order SMALLINT NULL` (unique per
--     class). Nullable on purpose: when a section is soft-deleted the app
--     code clears its display_order to NULL so its slot can be re-used by
--     a brand new section. The unique key still protects live rows because
--     MySQL's UNIQUE index allows multiple NULLs.
--   * Drop `description` (out of scope for ULP-4.0a; lecturer edits title only).
--   * Add soft-delete (`is_deleted`) and audit (`created_by`) columns.
--   * Tighten `title` length to 200 (form validation cap).
--   * Refresh indexes to match the new query patterns.

ALTER TABLE sections
    DROP FOREIGN KEY fk_section_course;

ALTER TABLE sections
    DROP INDEX idx_section_course;

ALTER TABLE sections
    DROP COLUMN course_id,
    DROP COLUMN description,
    DROP COLUMN sort_order;

ALTER TABLE sections
    ADD COLUMN class_id BIGINT NOT NULL AFTER id,
    ADD COLUMN display_order SMALLINT NULL AFTER title,
    ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER display_order,
    ADD COLUMN created_by BIGINT NULL AFTER updated_at,
    MODIFY COLUMN title VARCHAR(200) NOT NULL;

ALTER TABLE sections
    ADD CONSTRAINT fk_section_class
        FOREIGN KEY (class_id) REFERENCES classes(id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_section_creator
        FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD UNIQUE KEY uk_section_class_order (class_id, display_order),
    ADD INDEX idx_section_class_id (class_id, is_deleted);
