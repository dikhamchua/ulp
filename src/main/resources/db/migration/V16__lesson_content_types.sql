-- add-lesson-content-types — Sprint 3.
--
-- Adds the content_type discriminator + per-type columns to lessons so a
-- lesson can be RICHTEXT (existing), PDF (lesson_attachments row pinned
-- via pdf_attachment_id), or VIDEO (external URL or uploaded MP4 path).
--
-- Design references:
--   * D1 — single-table inheritance via content_type discriminator
--   * D2 — PDF body reuses lesson_attachments; lessons.pdf_attachment_id
--          binds the "main" attachment. ON DELETE SET NULL defense-in-depth
--          on top of the application-level FK clear in
--          LessonAttachmentsService.delete()
--   * D9 — existing rows backfill via the column default; every legacy
--          lesson already has a non-null content_richtext so the CHECK
--          passes at migration time without a data step
--
-- The CHECK constraint enforces type-specific column non-nullness so the
-- database refuses any half-built row regardless of which entry path was
-- used (form save, content endpoint, manual SQL). Existing rows pass
-- because they default to RICHTEXT and content_richtext is populated.

ALTER TABLE lessons
    ADD COLUMN content_type VARCHAR(20) NOT NULL DEFAULT 'RICHTEXT',
    ADD COLUMN pdf_attachment_id BIGINT NULL,
    ADD COLUMN video_url VARCHAR(500) NULL,
    ADD COLUMN video_provider VARCHAR(20) NULL;

ALTER TABLE lessons
    ADD CONSTRAINT chk_lesson_content_type
        CHECK (content_type IN ('RICHTEXT','PDF','VIDEO'));

-- Type-specific column non-null enforcement. RICHTEXT keeps existing rows
-- valid because they already carry a content_richtext value. PDF and VIDEO
-- rely on the lecturer uploading / pasting the required artifact through
-- the dedicated content endpoint before saving the type switch.
ALTER TABLE lessons
    ADD CONSTRAINT chk_lesson_content_shape CHECK (
        (content_type = 'RICHTEXT' AND content_richtext IS NOT NULL)
        OR (content_type = 'PDF' AND pdf_attachment_id IS NOT NULL)
        OR (content_type = 'VIDEO' AND video_url IS NOT NULL AND video_provider IS NOT NULL)
    );

ALTER TABLE lessons
    ADD CONSTRAINT fk_lesson_pdf_attachment
        FOREIGN KEY (pdf_attachment_id)
        REFERENCES lesson_attachments(id);
-- NOTE: no ON DELETE SET NULL referential action — MySQL 8.0 rejects
-- combining a SET NULL FK with a CHECK constraint on the same column
-- (error 3823). The application-level clear in
-- LessonAttachmentsService.delete() + LessonRepository.clearPdfAttachmentId
-- is the authoritative protection (design D2). With RESTRICT semantics
-- (default), failing to clear first surfaces loudly as a referential
-- integrity error rather than a silent NULL that violates the CHECK.

-- Speed up the reverse lookup "is this attachment any lesson's main PDF?"
-- used by LessonAttachmentsService.delete() before removing an attachment.
CREATE INDEX idx_lessons_pdf_attachment ON lessons(pdf_attachment_id);
