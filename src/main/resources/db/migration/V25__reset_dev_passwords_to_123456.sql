-- =============================================================================
-- ULP — V25__reset_dev_passwords_to_123456.sql
-- Reset password_hash of all non-deleted users to the BCrypt of "123456".
--
-- Why: local/dev test accounts previously used plaintext "password"; the team
-- wants a shorter shared password for demo and manual QA.
--
-- BCrypt (cost 10) for "123456":
--   $2a$10$QXT/sSbapXXHEWstiZbtT.oyHllsGWSF.E5C..Xl4SkMwYbfi.t5a
--
-- DEV ONLY. Do not apply this expectation to production credentials.
-- =============================================================================

UPDATE users
SET password_hash = '$2a$10$QXT/sSbapXXHEWstiZbtT.oyHllsGWSF.E5C..Xl4SkMwYbfi.t5a'
WHERE is_deleted = 0;
