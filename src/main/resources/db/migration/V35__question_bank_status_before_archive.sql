SET NAMES utf8mb4;

-- Remember the workflow status a question held right before it was archived so
-- that "unarchive" can restore it to its exact prior state (APPROVED→APPROVED,
-- REVIEW→REVIEW, etc.) instead of a fixed fallback. Nullable and transient: it
-- only carries meaning while workflow_status = 'ARCHIVED' and is cleared on
-- unarchive. Legacy rows archived before this column exists stay NULL and the
-- service falls back to REVIEW on restore. No CHECK constraint by design.

ALTER TABLE question_bank_items
    ADD COLUMN status_before_archive VARCHAR(20) NULL AFTER workflow_status;
