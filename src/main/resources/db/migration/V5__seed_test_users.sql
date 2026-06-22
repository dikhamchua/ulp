-- =============================================================================
-- ULP — V5__seed_test_users.sql
-- Them tai khoan test cho 3 vai tro con lai (LECTURER, HEAD, STUDENT)
-- de kiem thu phan quyen o Sprint 0.
--
-- Mat khau cho TAT CA tai khoan test: "password"
-- BCrypt hash: $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- (cung hash voi admin@ulp.edu.vn trong V2)
--
-- LUU Y: Chi dung cho moi truong DEV. KHONG seed tai khoan test o production.
-- =============================================================================

INSERT INTO users (email, password_hash, full_name, role, department_id, is_email_verified, is_active)
VALUES
('lecturer@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 'Giang Vien Test', 'LECTURER', 1, 1, 1),
('head@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 'Truong Bo Mon Test', 'HEAD', 1, 1, 1),
('student@ulp.edu.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
 'Sinh Vien Test', 'STUDENT', NULL, 1, 1);
