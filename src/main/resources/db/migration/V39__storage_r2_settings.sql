-- =============================================================================
-- ULP — V39__storage_r2_settings.sql
-- Seed STORAGE settings group (local | Cloudflare R2) and system.storage
-- permission for the admin storage settings screen.
-- =============================================================================

-- ── system_settings: STORAGE group ──────────────────────────────────────────
INSERT INTO system_settings (setting_key, setting_value, setting_group, description, is_encrypted)
SELECT * FROM (
    SELECT 'storage.provider' AS sk,
           'local' AS sv,
           'STORAGE' AS sg,
           'Active object storage provider: local | r2' AS ds,
           0 AS enc
    UNION ALL SELECT 'storage.r2.account_id', '', 'STORAGE',
           'Cloudflare account id (informational)', 0
    UNION ALL SELECT 'storage.r2.access_key_id', '', 'STORAGE',
           'R2 API access key id', 0
    UNION ALL SELECT 'storage.r2.secret_access_key', '', 'STORAGE',
           'R2 API secret access key (plain text MVP)', 0
    UNION ALL SELECT 'storage.r2.bucket', '', 'STORAGE',
           'R2 bucket name (private)', 0
    UNION ALL SELECT 'storage.r2.endpoint', '', 'STORAGE',
           'R2 S3 API endpoint, e.g. https://<accountid>.r2.cloudflarestorage.com', 0
    UNION ALL SELECT 'storage.r2.region', 'auto', 'STORAGE',
           'R2 region (use auto)', 0
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM system_settings s WHERE s.setting_key = src.sk
);

-- ── permissions catalogue ───────────────────────────────────────────────────
INSERT INTO permissions (feature_key, name, description, permission_group)
SELECT * FROM (
    SELECT 'system.storage' AS fk,
           'Cấu hình lưu trữ' AS nm,
           'Cấu hình object storage (local / Cloudflare R2)' AS ds,
           'SYSTEM' AS pg
) AS src
WHERE NOT EXISTS (
    SELECT 1 FROM permissions p WHERE p.feature_key = src.fk
);

-- Attach to ADMIN only (mirrors system.smtp / system.permissions style).
INSERT INTO role_permissions (role_code, permission_id)
SELECT 'ADMIN', p.id
FROM permissions p
WHERE p.feature_key = 'system.storage'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_code = 'ADMIN' AND rp.permission_id = p.id
  );
