-- =============================================================================
-- V18 — Flashcard card images + one-review-row-per-user-card constraint (ULP-5.x)
-- =============================================================================
-- 1. Optional image on each card side (deferred upload, magic-byte validated).
--    Stores a server-relative path (e.g. flashcards/{deckId}/<uuid>.png).
ALTER TABLE flashcards
    ADD COLUMN front_image VARCHAR(500) NULL AFTER back_text,
    ADD COLUMN back_image  VARCHAR(500) NULL AFTER front_image;

-- 2. Enforce exactly one SM-2 state row per (user, card) so ratings upsert
--    instead of appending a new row each review. Safe on empty dev data.
ALTER TABLE flashcard_reviews
    ADD CONSTRAINT uq_fr_user_card UNIQUE (user_id, flashcard_id);
