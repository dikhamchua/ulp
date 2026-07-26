-- =============================================================================
-- V38 — AI request logs
--
-- Records one row per AI provider *attempt*, not per logical request. When the
-- fallback chain tries OpenAI, gets a 429, and then succeeds on Groq, two rows
-- are written: one FAILED and one SUCCESS. That is what makes the table useful
-- for diagnosing which provider is actually carrying the traffic.
--
-- Metadata and token counts only. Prompt text and response text are
-- deliberately NOT stored: it keeps the table light, and it means the log never
-- accumulates student work should AI grading be added later.
--
-- provider_name is a snapshot column: ai_providers uses a hard DELETE, and the
-- name is what keeps the history readable after an admin removes a provider.
--
-- provider_id carries deliberately NO foreign key. An FK would make every log
-- insert take a shared lock on the parent ai_providers row, so any transaction
-- already holding that row exclusively would stall the AI request behind it —
-- paying a lock wait on the request path to write an observability row. The
-- constraint bought nothing to offset that: provider_name already survives the
-- delete, and no query joins or filters on provider_id. It stays as a plain
-- nullable reference for correlation while a provider still exists.
--
-- Token columns are nullable on purpose: a provider that omits the `usage`
-- object leaves them NULL. NULL means "unknown"; 0 would claim the call was
-- free, which is a different and false statement.
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE ai_request_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id BIGINT NULL,
    provider_name VARCHAR(100) NOT NULL,
    model VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    duration_ms INT NULL,
    error_message VARCHAR(500) NULL,
    source VARCHAR(50) NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ai_logs_created (created_at),
    INDEX idx_ai_logs_provider (provider_id),
    INDEX idx_ai_logs_status (status),
    CONSTRAINT fk_ai_logs_user FOREIGN KEY (created_by)
        REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
