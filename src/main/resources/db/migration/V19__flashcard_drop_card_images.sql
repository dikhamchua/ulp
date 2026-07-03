-- =============================================================================
-- V19 — Drop flashcard card-image columns (ULP-5.x)
-- =============================================================================
-- The card-image feature is fully removed; cards are text-only. Drop the two
-- image path columns added by V18. Hibernate validate no longer maps them.
ALTER TABLE flashcards
    DROP COLUMN front_image,
    DROP COLUMN back_image;
