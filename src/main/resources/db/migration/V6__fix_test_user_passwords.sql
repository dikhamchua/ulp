-- =============================================================================
-- ULP — V6__fix_test_user_passwords.sql
-- Dat lai mat khau cho TAT CA tai khoan test ve "password" (moi truong DEV).
--
-- Ly do: hash seed ban dau trong V2 (admin) khong khop voi plaintext nao da biet
-- nen khong the dang nhap. Migration nay dong bo hash cho ca 4 tai khoan test
-- ve cung mot gia tri BCrypt da kiem chung cho chuoi "password".
--
-- BCrypt hash cho "password": $2a$10$dTtO2C/fo28EF0SQ9lI3kO2gXa2GICyduasPVLYnBlAvLTLrHJwxa
--
-- LUU Y: Chi dung cho moi truong DEV. O production phai dat mat khau manh rieng.
-- =============================================================================

UPDATE users
SET password_hash = '$2a$10$dTtO2C/fo28EF0SQ9lI3kO2gXa2GICyduasPVLYnBlAvLTLrHJwxa'
WHERE email IN (
    'admin@ulp.edu.vn',
    'lecturer@ulp.edu.vn',
    'head@ulp.edu.vn',
    'student@ulp.edu.vn'
);
