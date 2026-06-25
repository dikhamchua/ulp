-- ─────────────────────────────────────────────────────────────────
-- V12: Seed sentinel CODE + LINK rows for every non-deleted class
-- lacking an active invite token of that type.
--
-- This migration ONLY inserts placeholder rows so the
-- {class_invite_codes} table reaches a known state on every classes
-- row that was created before Sprint 2.3 (Invite & Join). The
-- sentinel rows are inserted with is_active=0 so they NEVER resolve
-- at join time. After the application boots,
-- InviteCodeBackfillRunner replaces these sentinels with real
-- CODE+LINK active rows generated through InviteTokenGenerator and
-- then deletes the SEED-* sentinels.
--
-- Rationale (see openspec design.md Decision 6):
--   The CODE alphabet "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" cannot be
--   produced reliably in portable SQL. Keeping the token-format
--   invariant in the Java service avoids embedding alphabet logic in
--   SQL and keeps a single source of truth for token generation.
--
-- Schema widening:
--   V1 declared {class_invite_codes.code VARCHAR(20)}. Sprint 2.3's
--   LINK token is 32 chars (24 random bytes base64url-encoded
--   without padding). We widen the column to VARCHAR(40) so both
--   the 6-char CODE and the 32-char LINK fit comfortably, plus
--   headroom for the temporary {SEED-CODE-<id>}/{SEED-LINK-<id>}
--   sentinel values inserted below.
--
-- Idempotency:
--   The INSERT ... SELECT only fires when no row of the same type
--   exists for the given class_id. Rerunning the migration (e.g.
--   after a manual seed) is a no-op.
-- ─────────────────────────────────────────────────────────────────

ALTER TABLE class_invite_codes MODIFY COLUMN code VARCHAR(40) NOT NULL;

INSERT INTO class_invite_codes (class_id, code, type, is_active, use_count, max_uses, expires_at, created_by)
SELECT c.id,
       CONCAT('SEED-CODE-', c.id),
       'CODE',
       0,
       0,
       NULL,
       NULL,
       c.created_by
FROM classes c
WHERE c.is_deleted = 0
  AND NOT EXISTS (
        SELECT 1 FROM class_invite_codes ic
        WHERE ic.class_id = c.id AND ic.type = 'CODE'
  );

INSERT INTO class_invite_codes (class_id, code, type, is_active, use_count, max_uses, expires_at, created_by)
SELECT c.id,
       CONCAT('SEED-LINK-', c.id),
       'LINK',
       0,
       0,
       NULL,
       NULL,
       c.created_by
FROM classes c
WHERE c.is_deleted = 0
  AND NOT EXISTS (
        SELECT 1 FROM class_invite_codes ic
        WHERE ic.class_id = c.id AND ic.type = 'LINK'
  );
