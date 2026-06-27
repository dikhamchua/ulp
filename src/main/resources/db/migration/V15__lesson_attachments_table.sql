-- ULP-4.0c — Lesson attachments table.
--
-- Producer for ULP-4.3 (student PDF viewer) and ULP-4.4 (student file
-- download). Stores file metadata; the file body lives on the local
-- filesystem under uploads/lessons/{lessonId}/<uuid>.<ext>.
--
-- Design decisions captured at openspec/changes/add-lesson-attachments/design.md:
--   * D1 — Hard delete (no soft-delete flag); row + on-disk file removed
--     together. No is_deleted column.
--   * D2 — Cascade on lesson soft-delete is application-level
--     (LessonsService.delete calls deleteAllByLesson before markDeleted).
--     The FK ON DELETE CASCADE here is defense-in-depth for any future
--     hard-delete path on lessons.
--   * idx_la_lesson(lesson_id) — list query is "show all attachments of a
--     lesson"; the FK already creates an index on lesson_id under
--     InnoDB, but we keep an explicit named index for clarity.
--
-- Whitelist (enforced at the service layer, NOT in DB):
--   .pdf, .docx, .pptx, .xlsx, .zip (size ≤ 20MB).

CREATE TABLE lesson_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lesson_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_path VARCHAR(500) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_la_lesson (lesson_id),
    CONSTRAINT fk_la_lesson FOREIGN KEY (lesson_id)
        REFERENCES lessons(id) ON DELETE CASCADE,
    CONSTRAINT fk_la_uploader FOREIGN KEY (uploaded_by)
        REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
