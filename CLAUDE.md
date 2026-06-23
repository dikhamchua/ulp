# CLAUDE.md — ULP Project Guide

> Tài liệu định hướng cho coding agent (Claude Code, Codex, Cursor) khi làm việc
> với repo này. Đọc file này trước khi đụng vào code. Đây là project context,
> không phải agent shim — agent shim nằm ở `AGENTS.md`.

---

## 1. Tóm tắt project

**Tên**: ULP — University Learning Platform
**Loại**: Capstone project nhóm, web app dạy & học cho môi trường đại học
**Stack**: Spring Boot 3.4.4 + Java 17 + Thymeleaf (SSR) + MySQL 8.0 + Flyway
**Sprint hiện tại**: Đang trong Sprint 2 (Classes & Admin shell), một số mục
admin đang ở dạng placeholder chờ Sprint 6.

Các domain chính (theo schema):
- Identity & Auth (users, OAuth, password reset, login history)
- Organization (departments, categories)
- Courses & Classes (course → section → lesson, class → enrollment)
- Lessons & Content (rich text/PDF/video), learning progress
- Tests & Questions (MCQ/MR/fill-in/matching, attempts, responses)
- Assignments & Submissions (rubric, feedback)
- Flashcards (decks + SM-2 spaced repetition)
- Discussions (comments + moderation)
- Communication (notifications, messages)
- Admin & System (system_settings, feature_permissions)

---

## 2. Tech stack chi tiết

| Layer | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4.4 |
| Web | Spring MVC (server-rendered) |
| View | Thymeleaf + `thymeleaf-extras-springsecurity6` |
| Security | Spring Security (form login + Google OAuth2 client) |
| Persistence | Spring Data JPA / Hibernate (validate mode) |
| Migration | Flyway (MySQL 8) — owns schema |
| DB | MySQL 8.0, utf8mb4, InnoDB |
| Mail | `spring-boot-starter-mail` (JavaMailSender) |
| Validation | Jakarta Bean Validation |
| Tooling | Lombok, Spring DevTools |
| Test | JUnit 5 + Spring Security Test |
| Build | Maven (wrapper `mvnw` / `mvnw.cmd`) |
| Frontend assets | static CSS/JS dưới `src/main/resources/static/`, không bundler |

**Không dùng** (đừng tự ý thêm): React/Vue/Angular SPA, Webpack, Tailwind,
Node toolchain, Redis, Kafka, Docker compose. Project đang SSR thuần, giữ
simple đúng tinh thần KISS.

---

## 3. Cấu trúc thư mục

```
ulp/
├── AGENTS.md                       # agent shim (Harness)
├── CLAUDE.md                       # FILE NÀY — project context
├── README.md                       # repository-harness gốc
├── pom.xml                         # Maven config
├── mvnw / mvnw.cmd                 # Maven wrapper
├── docs/                           # Harness docs (FEATURE_INTAKE, ARCH, ...)
├── scripts/                        # Harness CLI binary
├── harness.db                      # Harness state (SQLite)
├── uploads/                        # User uploads (gitignored)
├── src/
│   ├── main/
│   │   ├── java/com/ulp/
│   │   │   ├── UlpApplication.java        # entry point
│   │   │   ├── admin/                     # Admin panel (controller/dto/service)
│   │   │   ├── auth/                      # Login, OAuth, password reset
│   │   │   ├── classes/                   # Class CRUD, members, enrollment
│   │   │   ├── profile/                   # User profile, change password
│   │   │   └── shared/                    # Cross-cutting
│   │   │       ├── config/                # SecurityConfig, WebConfig
│   │   │       ├── exception/             # GlobalExceptionHandler
│   │   │       ├── mail/                  # MailService
│   │   │       ├── upload/                # AvatarStorageService
│   │   │       └── web/                   # HomeController, shared web bits
│   │   └── resources/
│   │       ├── application.properties             # base config (no secrets)
│   │       ├── application-local.properties       # gitignored, real values
│   │       ├── application-local.properties.example  # template
│   │       ├── db/migration/V*__*.sql             # Flyway migrations
│   │       ├── templates/                         # Thymeleaf views
│   │       │   ├── admin/                         # dashboard, placeholder, ...
│   │       │   ├── auth/                          # login, forgot/reset
│   │       │   ├── classes/                       # form, detail-*, manage
│   │       │   └── fragments/                     # head, header, sidebar
│   │       └── static/css/, js/                   # plain CSS + vanilla JS
│   └── test/java/com/ulp/                # JUnit tests (Sprint*IntegrationTest, ...)
├── rev2.log / rev2.err.log         # runtime logs
└── target/                         # Maven build output
```

**Code organization rule**: feature-first packages (`auth/`, `classes/`,
`admin/`, `profile/`). Trong mỗi feature: `controller/`, `service/`,
`repository/`, `entity/`, `dto/`. Cross-cutting đặt ở `shared/`.

---

## 4. Database & Migration rules

**Flyway sở hữu schema**. Hibernate ở chế độ `validate` — KHÔNG được auto
update schema. Mọi thay đổi DB phải qua migration mới:

```
src/main/resources/db/migration/V<N>__<short_description>.sql
```

Migrations hiện có:
- `V1__init_schema.sql` — full schema (12 nhóm bảng)
- `V2__seed_data.sql` — seed dữ liệu
- `V3__activity_tables.sql`
- `V4__rbac_enhancement.sql`
- `V5__seed_test_users.sql`
- `V6__fix_test_user_passwords.sql`
- `V7__classes_drop_course_and_add_code.sql`
- `V8__seed_fake_students.sql`

**Quy tắc migration**:
- Đặt tên kiểu `V<N>__<snake_case_purpose>.sql` (2 dấu gạch dưới sau số)
- Không sửa migration đã commit; tạo migration mới để sửa lỗi
- Charset `utf8mb4`, engine `InnoDB`, dùng `DATETIME DEFAULT CURRENT_TIMESTAMP`
- FK theo pattern `fk_<short_table>_<ref>`; INDEX theo pattern `idx_<table>_<cols>`
- Status enum dùng `VARCHAR(20)` + `CHECK (status IN (...))`
- Soft-delete: cột `is_deleted TINYINT(1) DEFAULT 0` (đã có ở các bảng chính)

**Bảng quan trọng cho admin/settings**:
- `system_settings(setting_key, setting_value, setting_group, description, is_encrypted, updated_by)`
  — đã seed sẵn group GENERAL, SMTP, OAUTH, AI (xem V1 cuối file)
- `feature_permissions(role, feature_key, is_granted, updated_by)` — RBAC động

---

## 5. Authentication & Authorization

### Roles (4 cấp)

| Role | Mô tả | Truy cập |
|---|---|---|
| `STUDENT` | Sinh viên | learning, flashcard, test/assignment làm bài |
| `LECTURER` | Giảng viên | tạo class, lesson, test, chấm assignment |
| `HEAD` | Trưởng bộ môn | mọi quyền của LECTURER + duyệt content version |
| `ADMIN` | Quản trị hệ thống | toàn bộ — `/admin/**` |

Hằng số role dùng `com.ulp.auth.Roles.*`. SpEL constants có sẵn:
- `Roles.LECTURER_OR_ABOVE` → `'LECTURER','HEAD','ADMIN'`
- `Roles.PREAUTH_LECTURER_OR_ABOVE` → `hasAnyRole('LECTURER','HEAD','ADMIN')`

### URL protection (xem `SecurityConfig.java`)

```
permitAll  : /css/**, /js/**, /images/**, /fonts/**, /uploads/**,
             /login, /forgot-password, /reset-password
LECTURER+  : /lecturer/**
ADMIN      : /admin/**
authenticated : everything else
```

### Auth flow

- Form login: BCrypt password, fail handler redirect `/login?error`
- Google OAuth2: **conditional** — chỉ bật khi
  `spring.security.oauth2.client.registration.google.client-id` được cấu hình.
  Nếu thiếu, app vẫn chạy bình thường (auto-config back off).
- Password reset: token trong `password_reset_tokens`, gửi email qua
  `MailService` (cũng conditional theo `spring.mail.host`).

### Convention quan trọng

Mọi feature flag dạng "có config thì bật" dùng `@ConditionalOnProperty`.
Ví dụ Google OAuth chỉ enable khi `spring.security.oauth2.client.registration.google.client-id`
được cấu hình. Đây là pattern chuẩn cho project này — phải giữ.

**Ngoại lệ (Sprint 2)**: `MailService` đã chuyển từ `@ConditionalOnProperty`
sang DB-backed transport (`DbConfiguredMailSender`). Bean luôn tồn tại;
`send()` trả về `false` khi `system_settings.smtp.host` rỗng. Caller
(`PasswordRecoveryService`) check boolean return thay vì null-bean. SMTP
credentials cấu hình tại `/admin/settings/email`, không phải properties file.
Xem `docs/decisions/0008-smtp-password-plain-text.md` cho trade-off.

---

## 6. Cấu hình & Secrets

### File layout

```
application.properties             ← commit, không chứa secret
application-local.properties       ← .gitignored, chứa secret thật
application-local.properties.example ← template, commit
```

`application.properties` dùng `spring.config.import=optional:classpath:application-local.properties`
để nạp file local nếu có. Khi developer mới clone repo:

1. Copy `application-local.properties.example` → `application-local.properties`
2. Điền `DB_USERNAME`, `DB_PASSWORD`
3. (Tùy chọn) bỏ comment Google OAuth, SMTP

### Properties keys hiện dùng

| Key | Mặc định | Mục đích |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | root / root | MySQL credentials |
| `APP_BASE_URL` | `http://localhost:8080` | dùng trong link reset email |
| `UPLOAD_DIR` | `uploads` | thư mục lưu avatar |
| `spring.mail.host` (+port/username/password) | (vắng) | bật SMTP |
| `spring.security.oauth2.client.registration.google.*` | (vắng) | bật Google login |

### Quy tắc

- KHÔNG commit `application-local.properties`
- KHÔNG hard-code secret trong code Java hay `application.properties`
- KHÔNG đẩy DB credentials lên git, dù là dev/test
- Khi thêm property mới cần secret → cập nhật cả 3 file (properties, example, README/docs)

---

## 7. Admin Panel — hiện trạng

`AdminController` đã có 5 tab sidebar:

| Tab | URL | Trạng thái |
|---|---|---|
| Dashboard | `/admin/dashboard` | ✅ implemented (stats + recent classes) |
| Users | `/admin/users` | 🚧 placeholder |
| Departments | `/admin/departments` | 🚧 placeholder |
| Classes | `/admin/classes` | 🚧 placeholder |
| Settings | `/admin/settings` | ✅ index + Email sub-tab implemented |
| └ Email | `/admin/settings/email` | ✅ implemented (SMTP config + test send) |
| └ General | — | 🚧 placeholder card |
| └ OAuth | — | 🚧 placeholder card |

`placeholder.html` chỉ là khung chờ. Khi implement một tab:
- Thêm GET mapping riêng (không dùng chung handler `placeholder()`)
- Thêm template `admin/<tab>.html`
- Sidebar active tab qua `model.addAttribute("activeTab", "<tab>")`
- Permission: `@PreAuthorize("hasRole('" + Roles.ADMIN + "')")` (đã apply ở class level)

### Settings tab — kế hoạch nhóm Email (đã chốt scope trong conversation)

Storage dùng bảng `system_settings` sẵn có (key-value). Group `SMTP` đã được
seed sẵn với 5 keys: `smtp.host`, `smtp.port`, `smtp.username`,
`smtp.password`, `smtp.from_email`. Khi mở rộng cho email settings MVP, thêm:
- `smtp.encryption` (none|tls|ssl)
- `smtp.from_name`
- `smtp.reply_to`

Quyết định đã chốt:
- Scope: MVP (Transport + Sender + Test Send)
- Provider: SMTP only
- Templates: hard-code trong code (Sprint sau làm UI)
- Logs: chưa có
- Storage: bảng `system_settings` (đã có)
- Password: plain text — technical debt (xem decision record sẽ tạo khi implement)
- Permission: bất kỳ role `ADMIN` được vào
- Test fail: toast notification, không log

---

## 8. Build & Run

### Yêu cầu

- JDK 17+ (Temurin/Oracle/Adoptium đều OK)
- MySQL 8.0 chạy local, đã tạo database `ulp_db`
- Maven (dùng wrapper, không cần cài global)

### Khởi tạo lần đầu

```bash
# 1. Tạo DB
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ulp_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. Tạo file config local
copy src\main\resources\application-local.properties.example src\main\resources\application-local.properties
# rồi điền DB_USERNAME, DB_PASSWORD

# 3. Chạy app (Flyway tự migrate)
.\mvnw.cmd spring-boot:run
```

App lên ở `http://localhost:8080`.

### Lệnh thường dùng

```bash
.\mvnw.cmd clean                     # clean target/
.\mvnw.cmd compile                   # compile only
.\mvnw.cmd test                      # chạy unit + integration test
.\mvnw.cmd spring-boot:run           # dev run
.\mvnw.cmd package                   # build .jar (skip với -DskipTests nếu cần)
```

### Test users

Đã seed sẵn trong `V5__seed_test_users.sql` + `V6__fix_test_user_passwords.sql`.
Xem migration để lấy email + password mẫu.

---

## 9. Coding conventions

### Java

- Package theo feature (`com.ulp.<feature>.<layer>`)
- Layer: `controller` → `service` → `repository` → `entity`; `dto` cross-layer
- DTO dùng inner record/class trong `<Feature>Dtos.java` (xem `AuthDtos`,
  `ProfileDtos`, `ClassesDtos`, `MemberDtos`, `AdminDashboardDtos`)
- Service nhận DTO trả DTO; entity không leak ra controller
- Lombok OK cho getter/setter/builder; tránh `@Data` trên entity (equals/hashCode)
- Exception toàn cục qua `GlobalExceptionHandler`
- **Comment phải viết bằng tiếng Anh** (inline comment, block comment, javadoc).
  Không dùng tiếng Việt trong comment code Java. Javadoc bắt buộc trên public
  class/method. Lý do: tránh lẫn lộn encoding, đồng nhất với log message và
  identifier (đã tiếng Anh), thân thiện hơn cho tooling/IDE quốc tế.

### Thymeleaf

- Template dưới `templates/<feature>/<view>.html`
- Fragments dùng chung trong `templates/fragments/` (head, app-header,
  admin-sidebar, class-sidebar)
- Cấu trúc layout: `<head th:replace="~{fragments/head :: head}">` + page body
- Spring Security tag: `xmlns:sec="http://www.thymeleaf.org/extras/spring-security"`
- Không hardcode URL — dùng `@{/path}`

### Notifications & feedback

- **MỌI thông báo cho người dùng (success / error / warning / info) đều
  phải dùng toast `window.UlpToast`** (định nghĩa trong `static/js/app.js`,
  wrap iziToast). Không dùng `<div class="alert">` inline, không dùng
  `alert()` native, không tự viết banner.
- Pattern flash → toast: controller gọi
  `redirectAttributes.addFlashAttribute("flashSuccess"|"flashError", ...)`,
  template render `<div id="flash-data" data-flash-success=... data-flash-error=...>`,
  page-script drain `#flash-data` và gọi `UlpToast.success(...)` /
  `UlpToast.error(...)` (xem `admin.js`, `class-detail.js`, `login.html`).
- Lỗi validation form (BindingResult): hiển thị inline cạnh field — KHÔNG
  đẩy ra toast (toast cho thông báo top-level, không phải lỗi field).
- iziToast script + CSS đã load trong `fragments/head.html`. Trang nào
  không dùng fragment đó (ví dụ `login.html`) phải tự include CDN.

### CSS/JS

- Vanilla CSS dưới `static/css/<feature>.css`, vanilla JS `static/js/<feature>.js`
- Không thêm bundler / preprocessor
- File CSS hiện có: `main.css`, `app-shell.css`, `auth.css`, `admin.css`,
  `classes.css`, `class-detail.css`

### Test

- File test: `<Feature>IntegrationTest.java` cho integration,
  `<Class>Test.java` cho unit
- Sprint-level integration test: `Sprint<N><Topic>IntegrationTest.java`
- Dùng `@SpringBootTest` + `@AutoConfigureMockMvc`; security test dùng
  `@WithMockUser` hoặc `with(user(...))`

### File size

Giữ file Java dưới ~200 dòng khi có thể. File hiện có vài chỗ vượt — tách
khi đụng vào để refactor (đừng refactor file lành nếu không cần).

---

## 10. Harness integration

Repo có cài `repository-harness` (xem `AGENTS.md`, `docs/HARNESS.md`).

Trước khi implement task lớn:

1. Đọc `docs/FEATURE_INTAKE.md` để chọn lane (tiny / normal / high-risk)
2. Check `scripts/bin/harness-cli.exe query matrix` xem có tool/proof gì sẵn
3. High-risk story → tạo folder `docs/stories/<epic>/<id>/` với
   `overview.md`, `design.md`, `execplan.md`, `validation.md`
4. Decision quan trọng → ghi `docs/decisions/NNNN-<title>.md`

Lane phổ biến cho ULP:
- **Tiny**: sửa text, đổi label, fix typo, thêm field optional vào form
- **Normal**: thêm CRUD cho 1 entity, 1 API endpoint mới, 1 trang admin tab
- **High-risk**: đụng auth/RBAC, schema migration phá vỡ, OAuth provider mới,
  thay password encryption, đụng audit log

---

## 11. Things to NOT do

- ❌ Đổi `spring.jpa.hibernate.ddl-auto` thành `update`/`create` (Flyway sở hữu schema)
- ❌ Sửa migration đã commit
- ❌ Hardcode secret vào `application.properties` hoặc Java code
- ❌ Thêm bundler / SPA framework — project là SSR thuần
- ❌ Đặt SMTP credentials vào `application-local.properties` — đã chuyển sang `system_settings` table, edit qua `/admin/settings/email`
- ❌ Bỏ `@ConditionalOnProperty` cho integration optional — chỉ MailService
  được đặc cách (xem decision 0008) và Google OAuth được đặc cách (xem
  decision 0009, do credentials đã chuyển sang DB-backed registration)
- ❌ Bypass Spring Security bằng cách permit URL không cần thiết
- ❌ Auto-create file markdown summary/notes nếu user không yêu cầu
- ❌ Dùng `@Data` trên JPA entity (gây vòng lặp equals/hashCode)
- ❌ Trả entity trực tiếp từ controller — phải qua DTO
- ❌ Dùng inline `<div class="alert">` hoặc `alert()` native để báo thành
  công / thất bại cho user — luôn luôn dùng `UlpToast` (xem mục 9 ›
  Notifications & feedback). Field-level validation error vẫn render inline.

---

## 12. Ngôn ngữ phản hồi

**Trả lời người dùng bằng tiếng Việt có dấu đầy đủ** (theo global user
preference). Code, tên file, log message **và comment trong code** phải giữ
tiếng Anh (xem mục 9 — Coding conventions). Chỉ phần văn bản hiển thị cho
end-user (Thymeleaf template, flash message, error message UI) mới dùng
tiếng Việt.

---

## 13. Quick reference

| Câu hỏi | Đáp |
|---|---|
| App chạy port nào? | 8080 |
| DB name? | `ulp_db` (MySQL 8) |
| Migration ở đâu? | `src/main/resources/db/migration/` (V1–V9) |
| Admin URL? | `/admin/dashboard` |
| Login URL? | `/login` |
| Test framework? | JUnit 5 + Spring Test |
| Build command? | `.\mvnw.cmd spring-boot:run` |
| Settings bảng nào? | `system_settings` (key-value, đã seed) |
| Email settings UI? | `/admin/settings/email` (ADMIN only) |
| SMTP config nguồn? | `system_settings` table, key `smtp.*` (KHÔNG ở properties) |
| Roles? | STUDENT / LECTURER / HEAD / ADMIN |
| OAuth provider hỗ trợ? | Google (conditional) |
