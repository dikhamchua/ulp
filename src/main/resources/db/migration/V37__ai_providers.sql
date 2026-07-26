-- =============================================================================
-- ULP — V37__ai_providers.sql
-- AI Provider Settings (Sprint 8)
--
-- Admin-managed list of OpenAI-compatible chat providers. Each row is one
-- endpoint (name + base_url + model + api_key). AiClient walks the enabled
-- rows in display_order ASC and falls back to the next one on a transient
-- failure (network / timeout / 5xx / 429).
--
-- Why a dedicated table instead of system_settings: system_settings is a flat
-- key-value store with a UNIQUE setting_key, so it cannot hold N rows of the
-- same shape. The pre-existing 'ai.provider' / 'ai.api_key' placeholder rows
-- seeded by V1 stay untouched and unused.
--
-- display_order is NULLABLE on purpose: the UNIQUE KEY treats NULLs as
-- distinct in MySQL, so a deleted row frees its slot without forcing a
-- renumbering pass over the remaining rows.
--
-- api_key is stored in plain text — the same accepted technical debt as the
-- SMTP password (see docs/decisions/0008-smtp-password-plain-text.md).
--
-- Permission gate 'system.ai' already exists: seeded by V4 and granted to
-- ADMIN in the same migration. No permission rows are added here.
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE ai_providers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model VARCHAR(150) NOT NULL,
    api_key VARCHAR(500) NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    display_order SMALLINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_ai_providers_name (name),
    UNIQUE INDEX idx_ai_providers_display_order (display_order),
    INDEX idx_ai_providers_enabled_order (is_enabled, display_order),
    CONSTRAINT fk_ai_providers_updater FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
