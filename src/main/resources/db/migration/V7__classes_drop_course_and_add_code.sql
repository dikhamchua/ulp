-- ─────────────────────────────────────────────────────────────────
-- V7: Drop course dependency from classes, add Lecturer-facing code.
--
-- Reason:
--   Sprint 2 ships Lecturer Classes CRUD WITHOUT the Course capability,
--   so the FK classes.course_id → courses.id is removed entirely. When
--   Course CRUD is later implemented, a forward migration will re-add
--   the column.
--
-- Adds:
--   classes.code VARCHAR(10) NULL UNIQUE — Lecturer-facing 5-char class
--   identifier (separate concept from the SV invite code in
--   class_invite_codes). Auto-generated on create.
--
-- Verified names (from V1__init_schema.sql):
--   FK constraint  fk_class_course        (V1 line 200)
--   Index          idx_class_course       (V1 classes block)
-- ─────────────────────────────────────────────────────────────────

ALTER TABLE classes DROP FOREIGN KEY fk_class_course;
ALTER TABLE classes DROP INDEX idx_class_course;
ALTER TABLE classes DROP COLUMN course_id;

ALTER TABLE classes ADD COLUMN code VARCHAR(10) NULL AFTER name;
ALTER TABLE classes ADD UNIQUE INDEX uk_classes_code (code);
