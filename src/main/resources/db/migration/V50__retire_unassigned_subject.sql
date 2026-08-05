-- retire-unassigned-subject
-- Retires the per-department 'UNASSIGNED' placeholder seeded by V46 and
-- re-seeded by V49. Rows still pointing at it are reassigned to the
-- department's real subject, then the placeholder is soft-deleted.
-- Never hard-deleted: subject_activities and subject_chapters cascade on
-- delete and would lose audit history.

SET NAMES utf8mb4;

-- 1. Resolution table: department -> subject inheriting the placeholder rows.
--    MIN(id) makes the pick deterministic when a department has 2+ candidates.
--    Departments with no real subject are excluded, so they keep their
--    placeholder (question_bank_items.subject_id is NOT NULL since V49).
CREATE TEMPORARY TABLE tmp_unassigned_remap (
    department_id     BIGINT NOT NULL PRIMARY KEY,
    placeholder_id    BIGINT NOT NULL,
    target_subject_id BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT INTO tmp_unassigned_remap (department_id, placeholder_id, target_subject_id)
SELECT ph.department_id,
       ph.id,
       (SELECT MIN(r.id)
          FROM subjects r
         WHERE r.department_id = ph.department_id
           AND r.is_deleted = 0
           AND r.is_active = 1
           AND r.code <> 'UNASSIGNED')
FROM subjects ph
WHERE ph.code = 'UNASSIGNED'
  AND ph.is_deleted = 0
  AND EXISTS (SELECT 1
                FROM subjects r
               WHERE r.department_id = ph.department_id
                 AND r.is_deleted = 0
                 AND r.is_active = 1
                 AND r.code <> 'UNASSIGNED');

-- 2. Move bank items onto the real subject. chapter_id is cleared in the same
--    statement: those chapters belong to the placeholder and would otherwise
--    describe a different subject than the item, which the picker filter reads.
UPDATE question_bank_items qbi
    INNER JOIN tmp_unassigned_remap m
        ON m.placeholder_id = qbi.subject_id
SET qbi.subject_id = m.target_subject_id,
    qbi.chapter_id = NULL;

-- 3. Move classes onto the real subject. department_id already matches
--    (placeholder and target share it), so only subject_id changes.
UPDATE classes c
    INNER JOIN tmp_unassigned_remap m
        ON m.placeholder_id = c.subject_id
SET c.subject_id = m.target_subject_id;

-- 4. Soft-delete the placeholder chapters. The placeholder was never visible in
--    any UI, so these are backfill artefacts, not authored content; re-homing
--    them would collide with uk_subj_ch_subject_order for no user-facing gain.
UPDATE subject_chapters ch
    INNER JOIN tmp_unassigned_remap m
        ON m.placeholder_id = ch.subject_id
SET ch.is_deleted    = 1,
    ch.display_order = NULL,
    ch.updated_at    = CURRENT_TIMESTAMP
WHERE ch.is_deleted = 0;

-- 5. Soft-delete the placeholder. is_deleted = 1 nulls live_code, releasing the
--    (department_id,'UNASSIGNED') slot on uk_subjects_dept_live_code — the
--    SubjectService guard shipped with this migration is what stops a new one.
--    is_active is forced to 0 too, so no picker surfaces it even if a future
--    query forgets the is_deleted filter.
UPDATE subjects s
    INNER JOIN tmp_unassigned_remap m
        ON m.placeholder_id = s.id
SET s.is_deleted = 1,
    s.is_active  = 0,
    s.updated_at = CURRENT_TIMESTAMP;

-- 6. Any placeholder that survived step 5 (department had no real subject) must
--    at least stay inactive. No-op on a healthy database.
UPDATE subjects
SET is_active = 0
WHERE code = 'UNASSIGNED'
  AND is_deleted = 0
  AND is_active <> 0;

DROP TEMPORARY TABLE tmp_unassigned_remap;
