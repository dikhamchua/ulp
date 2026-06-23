-- =============================================================================
-- ULP — V11__lowercase_existing_emails.sql
-- Back-fill historical mixed-case email addresses to lowercase so the new
-- case-insensitive lookup in CustomUserDetailsService and the lowercased
-- create-form path agree on a single canonical form.
--
-- Safety note: the V1 unique index `idx_users_email` uses MySQL's default
-- collation `utf8mb4_unicode_ci`, which is case-insensitive at write time.
-- That means historical rows cannot already contain two values that differ
-- only by case (the insert would have failed). Therefore lowering case here
-- cannot create a UNIQUE constraint violation.
-- =============================================================================

UPDATE users
SET email = LOWER(email)
WHERE email <> LOWER(email);
