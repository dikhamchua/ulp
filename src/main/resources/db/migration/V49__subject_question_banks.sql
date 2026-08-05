-- subject-question-banks
-- Reorganise question_bank_items by subject → chapter and split the bank into
-- two ownership scopes: HEAD bank (owner_id NULL, department-owned) and
-- lecturer-private bank (owner_id = user.id). Drops the review workflow and the
-- flat question_bank_categories taxonomy.

SET NAMES utf8mb4;

-- 1. New ownership + taxonomy columns (nullable so legacy rows can be backfilled).
ALTER TABLE question_bank_items
    ADD COLUMN owner_id BIGINT NULL AFTER department_id,
    ADD COLUMN subject_id BIGINT NULL AFTER category_id,
    ADD COLUMN chapter_id BIGINT NULL AFTER subject_id;

-- 2. Ensure a UNASSIGNED placeholder exists for every department that has bank
--    items but was created after V46 (so it never got the seeded placeholder).
--    Without this, the backfill below would strand legacy rows and the
--    MODIFY subject_id NOT NULL below would fail mid-migration (MySQL DDL is
--    not transactional).
INSERT INTO subjects (department_id, code, title, description, is_active, is_deleted, created_at, updated_at)
SELECT qbi.department_id,
       'UNASSIGNED',
       'Chưa phân môn',
       'Placeholder for legacy classes without a real subject binding',
       0,
       0,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (SELECT DISTINCT department_id FROM question_bank_items WHERE subject_id IS NULL) qbi
WHERE NOT EXISTS (
    SELECT 1 FROM subjects s
    WHERE s.department_id = qbi.department_id
      AND s.code = 'UNASSIGNED'
      AND s.is_deleted = 0
);

-- 3. Backfill subject_id from the per-department UNASSIGNED placeholder (V46).
--    owner_id stays NULL: every legacy row becomes a HEAD-bank item.
UPDATE question_bank_items qbi
    INNER JOIN subjects s
        ON s.department_id = qbi.department_id
       AND s.code = 'UNASSIGNED'
       AND s.is_deleted = 0
SET qbi.subject_id = s.id
WHERE qbi.subject_id IS NULL;

-- 4. Lock subject_id to NOT NULL and wire the new FKs.
ALTER TABLE question_bank_items
    MODIFY subject_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_qbi_subject FOREIGN KEY (subject_id) REFERENCES subjects(id),
    ADD CONSTRAINT fk_qbi_chapter FOREIGN KEY (chapter_id) REFERENCES subject_chapters(id),
    ADD CONSTRAINT fk_qbi_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE SET NULL;

-- 5. Migrate the workflow values onto a fresh `status` column. A new column is
--    used instead of renaming in place because the legacy workflow_status CHECK
--    (auto-named, DRAFT/REVIEW/APPROVED/REJECTED/ARCHIVED) rejects the value
--    'ACTIVE', and depending on the generated constraint name is fragile.
ALTER TABLE question_bank_items
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' AFTER workflow_status;

-- DRAFT/REVIEW/APPROVED/REJECTED already land on the ACTIVE default; only
-- ARCHIVED needs an explicit move.
UPDATE question_bank_items
SET status = 'ARCHIVED'
WHERE workflow_status = 'ARCHIVED';

-- 6. Drop the legacy column (removing its CHECK + indexes automatically) and
--    install the new (ACTIVE, ARCHIVED) CHECK on status.
ALTER TABLE question_bank_items
    DROP COLUMN workflow_status,
    ADD CONSTRAINT chk_qbi_status CHECK (status IN ('ACTIVE','ARCHIVED'));

-- 7. Drop the legacy category + review columns (FKs first; the indexes on
--    category_id / reviewed_by are dropped automatically with their columns).
ALTER TABLE question_bank_items
    DROP FOREIGN KEY fk_qbi_category,
    DROP FOREIGN KEY fk_qbi_reviewer,
    DROP COLUMN category_id,
    DROP COLUMN reviewed_by,
    DROP COLUMN review_note,
    DROP COLUMN approved_at,
    DROP COLUMN reviewed_at;

-- 8. The flat category taxonomy is fully superseded; its rows are demo data
--    only (feature is dev/unmerged). Verify the row count in the changelog
--    before this runs, then drop the table.
--    Expected: question_bank_categories rows are 0 or a handful of demo rows.
DROP TABLE question_bank_categories;
