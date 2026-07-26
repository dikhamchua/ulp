-- =============================================================================
-- ULP — V40__ai_system_prompts.sql
-- AI System Prompt catalog (Sprint 8)
--
-- Admin-managed catalog of named system prompts. Each row is one reusable
-- instruction block (name + description + content). Screens that call AI pick
-- a prompt by name from a dropdown; this migration only creates the catalog,
-- it does not wire anything into AiClient.
--
-- Why a dedicated table instead of system_settings: system_settings is a flat
-- key-value store with a UNIQUE setting_key, so it cannot hold N rows of the
-- same shape, and a prompt body is far larger than that column allows.
--
-- No display_order column, unlike ai_providers: there is no fallback chain
-- here. Prompts are selected explicitly by name, never walked in order.
--
-- Permission gate 'system.ai' already exists: seeded by V4 and granted to
-- ADMIN in the same migration. No permission rows are added here.
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE ai_system_prompts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    content TEXT NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    updated_by BIGINT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_ai_system_prompts_name (name),
    INDEX idx_ai_system_prompts_enabled (is_enabled),
    CONSTRAINT fk_ai_system_prompts_updater FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
