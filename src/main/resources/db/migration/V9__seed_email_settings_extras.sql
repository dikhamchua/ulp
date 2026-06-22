-- =============================================================================
-- V9__seed_email_settings_extras.sql
-- Bo sung 3 setting_key cho Email Settings MVP:
--   - smtp.encryption  : 'none' | 'tls' | 'ssl'  (default 'tls')
--   - smtp.from_name   : Display name cho From header (default 'ULP')
--   - smtp.reply_to    : Reply-To address optional (default '')
--
-- ON DUPLICATE KEY UPDATE setting_value = setting_value: no-op khi key da ton tai
-- => migration hoan toan idempotent, KHONG ghi de gia tri da co.
--
-- KHONG add column moi. KHONG modify row cu.
-- Masking smtp.password duoc xu ly o service layer (hardcoded SECRET_KEYS set),
-- khong dung column flag.
-- =============================================================================

INSERT INTO system_settings (setting_key, setting_value, setting_group, description) VALUES
('smtp.encryption', 'tls', 'SMTP', 'SMTP encryption: none, tls, hoac ssl'),
('smtp.from_name',  'ULP', 'SMTP', 'Display name trong From header'),
('smtp.reply_to',   '',    'SMTP', 'Reply-To address (optional)')
ON DUPLICATE KEY UPDATE setting_value = setting_value;
