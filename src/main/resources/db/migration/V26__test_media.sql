-- Optional fixed media on exams (YouTube / external VIDEO / external AUDIO).
SET NAMES utf8mb4;

ALTER TABLE tests
    ADD COLUMN media_type VARCHAR(20) NULL AFTER time_mode,
    ADD COLUMN media_url VARCHAR(1000) NULL AFTER media_type,
    ADD CONSTRAINT chk_test_media_type CHECK (
        media_type IS NULL OR media_type IN ('YOUTUBE', 'VIDEO', 'AUDIO')
    );
