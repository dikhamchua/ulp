# ULP — University Learning Platform

Nền tảng dạy & học web cho môi trường đại học (capstone).  
Stack: **Spring Boot 3.4.4 · Java 17 · Thymeleaf (SSR) · MySQL 8 · Flyway · Spring Security**.

| | |
|---|---|
| App URL | http://localhost:8080 |
| Login | http://localhost:8080/login |
| Admin | http://localhost:8080/admin/dashboard |

---

## Yêu cầu

- JDK **17+**
- MySQL **8.0** (local hoặc Docker) — database `ulp_db`
- Maven Wrapper (`mvnw` / `mvnw.cmd`) — không cần cài Maven global

### MySQL bằng Docker (khuyến nghị)

```bash
docker run -d --name mysql-8.0.36 \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 3306:3306 \
  mysql:8.0.36-debian
```

Nếu container đã có sẵn:

```bash
docker start mysql-8.0.36
```

Tạo database:

```bash
docker exec -i mysql-8.0.36 mysql -uroot -proot -e \
  "CREATE DATABASE IF NOT EXISTS ulp_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

---

## Cài đặt lần đầu

```bash
# 1. Clone
git clone https://github.com/dikhamchua/ulp.git
cd ulp

# 2. Config local (không commit file này)
copy src\main\resources\application-local.properties.example ^
     src\main\resources\application-local.properties
# rồi điền DB_USERNAME / DB_PASSWORD

# 3. Chạy app (Flyway tự migrate)
.\mvnw.cmd spring-boot:run
```

Trên macOS/Linux:

```bash
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
./mvnw spring-boot:run
```

App lên tại **http://localhost:8080**.

---

## Lệnh thường dùng

| Lệnh | Mục đích |
|---|---|
| `.\mvnw.cmd spring-boot:run` | Chạy dev |
| `.\mvnw.cmd test` | Unit + integration tests |
| `.\mvnw.cmd compile` | Compile only |
| `.\mvnw.cmd clean package` | Build JAR |

---

## Tài khoản test (DEV)

Seed bởi Flyway (`V5` + `V6`). Mật khẩu chung: **`password`**

| Email | Role |
|---|---|
| `admin@ulp.edu.vn` | ADMIN |
| `lecturer@ulp.edu.vn` | LECTURER |
| `head@ulp.edu.vn` | HEAD |
| `student@ulp.edu.vn` | STUDENT |

> Chỉ dùng trên môi trường DEV. Không seed tài khoản test lên production.

---

## Cấu trúc chính

```text
ulp/
├── src/main/java/com/ulp/
│   ├── features/          # feature-first packages
│   │   ├── auth/
│   │   ├── classes/
│   │   ├── student/
│   │   ├── admin/
│   │   └── ...
│   ├── entities/
│   ├── security/
│   └── shared/ / config/
├── src/main/resources/
│   ├── application.properties
│   ├── application-local.properties.example
│   ├── db/migration/      # Flyway V1…Vn
│   ├── templates/         # Thymeleaf
│   └── static/css|js/
└── docs/                  # harness, decisions, stories
```

---

## Domain chính

- Identity & Auth (form login, Google OAuth optional, password reset)
- Classes & enrollment (mã mời CODE/LINK, duyệt PENDING)
- Lessons & content (rich text / PDF / video)
- Tests (MCQ online exams)
- Assignments & submissions
- Flashcards (SM-2)
- Discussions / comments
- Messaging & notifications
- Admin (users, departments, settings, email SMTP)

---

## Cấu hình quan trọng

| Key / nguồn | Mục đích |
|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | MySQL credentials (`application-local.properties`) |
| `APP_BASE_URL` | Base URL (link reset password) — mặc định `http://localhost:8080` |
| `UPLOAD_DIR` | Thư mục upload — mặc định `uploads/` |
| `system_settings` (DB) | SMTP email — chỉnh tại `/admin/settings/email` |
| Google OAuth keys | Optional; bỏ comment trong `application-local.properties` để bật |

**Quy tắc:**

- Không commit `application-local.properties`
- Không hard-code secret
- Flyway sở hữu schema — `spring.jpa.hibernate.ddl-auto=validate`
- Không sửa migration đã commit; tạo migration mới

---

## Roles

| Role | Quyền chính |
|---|---|
| `STUDENT` | Học, làm bài, flashcard |
| `LECTURER` | Tạo lớp, bài giảng, test, chấm bài |
| `HEAD` | LECTURER + duyệt content |
| `ADMIN` | Toàn hệ thống `/admin/**` |

---

## Agent / Harness

Repo có agent harness cho coding agents:

- `AGENTS.md` — shim agent
- `CLAUDE.md` — project context cho Claude Code
- `docs/FEATURE_INTAKE.md` — phân loại risk
- `docs/decisions/` — decision records
- `docs/stories/` — story packets

Chi tiết: xem `docs/HARNESS.md`.

---

## License / Ghi chú

Capstone project — môi trường học thuật.  
Dev only secrets & seed users; harden trước khi deploy production.
