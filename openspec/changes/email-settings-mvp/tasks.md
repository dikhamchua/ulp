## 1. Database & migration

- [x] 1.1 Create Flyway migration `src/main/resources/db/migration/V9__seed_email_settings_extras.sql`
- [x] 1.2 INSERT 3 rows with `ON DUPLICATE KEY UPDATE setting_value = setting_value` (no-op-on-conflict, fully idempotent): `smtp.encryption='tls'`, `smtp.from_name='ULP'`, `smtp.reply_to=''` — all `setting_group='SMTP'`
- [x] 1.3 Do NOT add new columns. Do NOT touch existing rows. (No `ALTER TABLE`, no `UPDATE`.)
- [x] 1.4 Run `.\mvnw.cmd spring-boot:run` on a clean local DB and verify Flyway applies V9 without error _(verified via `./mvnw test` — Flyway logs show "Current version of schema ulp_db: 9", migration applied)_
- [x] 1.5 Re-run on the same DB after migration is recorded and verify Flyway reports `Up to date` without errors _(verified — "Schema ulp_db is up to date. No migration necessary." in test output)_ ← (verify: migration is idempotent — re-running on a populated DB does not duplicate rows or fail; no schema mutation occurred)

## 2. Settings entity & repository

- [x] 2.1 Create package `com.ulp.shared.settings`
- [x] 2.2 Create entity `SystemSetting` mapped to `system_settings` with fields ONLY for columns that actually exist in V1 schema: `id`, `settingKey` (`setting_key`), `settingValue` (`setting_value`), `settingGroup` (`setting_group`), `description`, `isEncrypted` (`is_encrypted` — `boolean`), `updatedBy` (`updated_by`), `createdAt`, `updatedAt`. **Do NOT add an `isSecret` field** — that column does not exist in the schema; masking is handled by the service layer's hardcoded key set.
- [x] 2.3 Create `SystemSettingsRepository extends JpaRepository<SystemSetting, Long>` with: `findBySettingGroup(String group)` returning `List<SystemSetting>`, `findBySettingKey(String key)` returning `Optional<SystemSetting>`
- [x] 2.4 Add a small helper method `Map<String,String> loadGroupAsMap(String group)` on the repository or a thin service to flatten `List<SystemSetting>` into a key→value map
- [x] 2.5 Boot the app locally; confirm Hibernate `validate` ddl-auto passes startup (no `SchemaManagementException`) _(verified — all 114 tests pass, including @SpringBootTest contexts which run Hibernate validate)_ ← (verify: entity field types match V1 column types exactly — `is_encrypted` is `TINYINT(1)` so Java side must be `boolean` or `Boolean`, NOT `Integer`; no missing or extra columns)

## 3. DB-backed mail transport

- [x] 3.1 Create class `com.ulp.shared.mail.DbConfiguredMailSender` (Spring `@Component`) with constructor injection of `SystemSettingsRepository`
- [x] 3.2 Implement `boolean send(String to, String subject, String body)`: load SMTP map via `repository.loadGroupAsMap("SMTP")`, short-circuit to `false` with warn-log when `smtp.host` is empty/blank
- [x] 3.3 In the same method, build a fresh `JavaMailSenderImpl` per call: set host, port (parse int, fall back to 587 on parse fail with warn-log), username, password; set `mail.smtp.starttls.enable=true` when `smtp.encryption=tls`; set `mail.smtp.ssl.enable=true` when `ssl`; neither when `none`. **Use the correct lowercase JavaMail property keys**: `mail.smtp.connectiontimeout=10000` and `mail.smtp.timeout=10000` (NOT camelCase — silently ignored otherwise).
- [x] 3.4 Construct a `MimeMessage`; set From as `"<from_name>" <from_email>` (using `InternetAddress(email, personal)`) when `from_name` non-empty, else bare `from_email`; set Reply-To when `smtp.reply_to` non-empty
- [x] 3.5 Wrap `mailSender.send(message)` in `try/catch (MailException | MessagingException | UnsupportedEncodingException)`: log warning with the exception message, return `false`
- [x] 3.6 Return `true` on success and add a single info-level log line ← (verify: send() with empty `smtp.host` never throws and returns false; send() with bad host hits the 10s timeout and returns false within ~10-12s, not hangs; success path logs once)

## 4. Refactor MailService + PasswordRecoveryService

- [x] 4.1 Remove `@ConditionalOnProperty("spring.mail.host")` from `MailService`. Remove the `JavaMailSender` dependency entirely.
- [x] 4.2 Replace `MailService` internals: constructor-inject `DbConfiguredMailSender`; `send(to, subject, body)` simply delegates to `dbConfiguredMailSender.send(to, subject, body)` and forwards the boolean return.
- [x] 4.3 Refactor `PasswordRecoveryService` (`src/main/java/com/ulp/auth/service/PasswordRecoveryService.java`):
  - Remove the field `@Autowired(required = false) private MailService mailService;`
  - Add `MailService mailService` to the constructor (final field, required injection)
  - In `requestReset()`, replace the `if (mailService != null) { ... } else { log link ... }` block with a boolean-return check: always call `mailService.send(...)`, then `if (!sent) { log.info("Mail not configured or send failed — token created for {}. Link: {}", user.getEmail(), link); }`
  - Keep the existing "Failed to send password-reset email" log line OR fold it into the new boolean-check fallback log; pick one, not both.
- [x] 4.4 Update `application-local.properties.example`: comment out the entire SMTP block more aggressively and add a banner comment "SMTP is now configured at /admin/settings/email (DB-backed). The block below is LEGACY and unread at runtime."
- [x] 4.5 Run `.\mvnw.cmd test` and verify `AuthLoginIntegrationTest` and `Sprint1AuthIntegrationTest` still pass _(verified — all 114 tests pass)_ ← (verify: with `smtp.host` empty in DB, `PasswordRecoveryService.requestReset()` writes the reset token AND logs the reset link to console; with `smtp.host` populated to a Mailtrap sandbox, the same flow sends a real email; old `mailService != null` branch is gone from the source)

## 5. Settings service layer

- [x] 5.1 Create package `com.ulp.admin.settings`
- [x] 5.2 Create DTO file `com.ulp.admin.settings.dto.EmailSettingsDtos.java` with two nested records:
  - `EmailSettingsForm(String host, Integer port, String encryption, String username, String password, String fromName, String fromEmail, String replyTo)` — **port is `Integer`, not `String`**, so Spring's converter rejects non-numeric input with a `typeMismatch` binding error (matches spec scenario "port out of range or non-numeric")
  - `TestResult(boolean ok, String error)` (return type of `sendTest()`)
- [x] 5.3 Add Bean Validation annotations on `EmailSettingsForm`: `@NotBlank` on `host`; `@NotNull @Min(1) @Max(65535)` on `port`; `@Pattern(regexp="none|tls|ssl")` on `encryption`; `@NotBlank @Size(max=255)` on `username`; `@NotBlank @Size(max=100)` on `fromName`; `@NotBlank @Email` on `fromEmail`; `@Email` (no `@NotBlank`) on `replyTo` so empty is allowed; `password` has no annotations (empty means "keep current")
- [x] 5.4 Create `EmailSettingsService` with `load()`, `save(EmailSettingsForm form, Long currentUserId)`, `sendTest(String to)`. Define `private static final Set<String> SECRET_KEYS = Set.of("smtp.password");` and `private static final String MASKED = "********";` as class constants.
- [x] 5.5 `load()`: read group `SMTP` rows via repository; build a form where for any key in `SECRET_KEYS` the value rendered is `MASKED`; for `port`, parse the string DB value to `Integer` (null on parse failure)
- [x] 5.6 `@Transactional public void save(EmailSettingsForm form, Long currentUserId)`: write each `smtp.*` row. For the `smtp.password` row specifically: if `form.password()` is null/blank OR equals `MASKED`, skip the row entirely (do not even touch `updated_by`/`updated_at` on that row); otherwise upsert with the new value.
- [x] 5.7 `save()`: for non-skipped rows, stamp `updated_by = currentUserId` and `updated_at = now()`. Use `INSERT ... ON DUPLICATE KEY UPDATE` semantics (either via a native query in the repository or fetch-then-save).
- [x] 5.8 `sendTest(String to)`: validate `to` via `new InternetAddress(to).validate()` in a try/catch; if invalid return `new TestResult(false, "Invalid recipient email address")` without calling MailService; otherwise build subject `"ULP — SMTP test email"`, body `"This is a test email from ULP. If you received this, your SMTP configuration works."`, call `mailService.send(...)`, and translate the boolean to `TestResult`. On `false`, return `TestResult(false, "SMTP host is not configured")` if DB host is blank; else `TestResult(false, "<exception message captured by DbConfiguredMailSender>")` — note: `MailService.send` does not currently surface the exception message. **Sub-task 5.9 fixes this.**
- [x] 5.9 Extend `DbConfiguredMailSender` (and `MailService`) with a second method `SendResult sendWithDetail(String to, String subject, String body)` returning a record `SendResult(boolean ok, String errorMessage)` so the service can surface the underlying SMTP error in the `TestResult`. Keep the existing `boolean send(...)` for backward compat with `PasswordRecoveryService`. ← (verify: empty-password save preserves existing password row byte-for-byte; submitting `MASKED` placeholder verbatim also preserves; invalid encryption rejected with field error; sendTest with empty host returns ok=false with "SMTP host is not configured"; save() is `@Transactional` — annotation is present and not on a non-public method)

## 6. Controllers & routing

- [x] 6.1 Create `com.ulp.admin.settings.controller.EmailSettingsController` with class-level `@Controller`, `@PreAuthorize("hasRole('" + Roles.ADMIN + "')")`, and `@RequestMapping("/admin/settings/email")`
- [x] 6.2 Implement `GET /` → call `service.load()`, populate model with `form`, `activeTab="settings"`, default `testRecipient` = currently-authenticated user's email (via `@AuthenticationPrincipal UlpUserDetails principal`), return view `admin/settings-email`
- [x] 6.3 Implement `POST /` accepting `@Valid @ModelAttribute("form") EmailSettingsForm form` + `BindingResult result`; on errors re-render `admin/settings-email` (HTTP 200) with field errors; on success call `service.save(form, principal.getId())`, then `RedirectAttributes` flash `success=true` and `redirect:/admin/settings/email`
- [x] 6.4 Implement `@PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)` returning `@ResponseBody TestResult` — parameter `@RequestParam("testRecipient") String testRecipient`; delegate to `service.sendTest(testRecipient)`
- [x] 6.5 Update `AdminController`:
  - Remove `"/settings"` from `placeholder()`'s `@GetMapping` array — final array becomes `{"/users","/departments","/classes"}`
  - Remove the `case "settings"` line from `labelFor()`
  - Add new method `@GetMapping("/settings") public String settingsIndex(Model model)` that calls `populateSidebar(model, "settings")` and returns view `admin/settings`
- [x] 6.6 Confirm `SecurityConfig` `/admin/**` rule still covers the new sub-paths (it does — `hasRole(ADMIN)` matches `/admin/settings`, `/admin/settings/email`, `/admin/settings/email/test`) ← (verify: anonymous GET on `/admin/settings/email` redirects to `/login`; STUDENT/LECTURER/HEAD GET returns 403; ADMIN GET returns 200; CSRF token present in form; POST `/test` accepts the X-CSRF-TOKEN header)

## 7. Views, fragments & static assets

- [x] 7.1 Edit `src/main/resources/templates/fragments/head.html`: add two new tags inside the `<head th:fragment="head(title, extraCss)">` block, immediately after the `<meta name="viewport">` line:
  ```html
  <meta name="_csrf" th:content="${_csrf?.token}"/>
  <meta name="_csrf_header" th:content="${_csrf?.headerName}"/>
  ```
  Use the safe-navigation `${_csrf?.token}` so pages without CSRF (none in current routes) still render.
- [x] 7.2 Create `src/main/resources/templates/admin/settings.html` (group index — card grid with one card: "Email" linking to `/admin/settings/email`). Must contain the string `Cài đặt hệ thống` to satisfy the renamed integration test from task 8.2.
- [x] 7.3 Create `src/main/resources/templates/admin/settings-email.html` with form fields per design.md (host, port, encryption as radio group `{none, tls, ssl}`, username, password masked, from_name, from_email, reply_to) and Save button. Pre-populate password input `value` attribute with `********`.
- [x] 7.4 Add a "Send Test Email" panel: input `id="testRecipient"` (pre-filled with current user's email via Thymeleaf), button `id="sendTestBtn"`, no toast container needed (UlpToast manages its own DOM)
- [x] 7.5 Create `src/main/resources/static/js/admin-settings.js`:
  - On `DOMContentLoaded`, bind click handler on `#sendTestBtn`
  - Read CSRF token + header from `<meta name="_csrf">` / `<meta name="_csrf_header">`
  - `fetch('/admin/settings/email/test', { method: 'POST', headers: { '[csrf-header]': '[token]', 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'testRecipient=' + encodeURIComponent(input.value) })`
  - Parse JSON `{ok, error}`. On `ok: true` call `window.UlpToast.success('Đã gửi email thử')`. On `ok: false` call `window.UlpToast.error(json.error || 'Gửi thất bại')`.
- [x] 7.6 Wire script tags in `admin/settings-email.html`: load order must be `app.js` (defines `UlpToast`) THEN `admin-settings.js`. Reference `app.js` via the same pattern as other admin templates.
- [x] 7.7 Add CSS rules to `static/css/admin.css` for the form layout (label/input grid). Minimal — no new file.
- [x] 7.8 Update `fragments/admin-sidebar.html` only if the active state for `activeTab == 'settings'` is not already handled (likely already is — confirm by reading the fragment) ← (verify: form renders with seeded values; password input renders `value="********"`; both `<meta name="_csrf">` tags present and non-empty when viewed as ADMIN; clicking "Send Test Email" hits the endpoint with the CSRF header and renders a UlpToast)

## 8. Tests

- [x] 8.1 Create `EmailSettingsServiceTest` (Mockito + JUnit 5) covering: load returns `********` for password; load returns `null` port when stored value is non-numeric; save with empty password input skips password row entirely (verify with mock repository `verify(...)` calls); save with `********` placeholder also skips password row; save with explicit new password overwrites; validation errors propagate (or assume controller layer catches — write at least one test that triggers a non-trivial code path); `sendTest` with invalid recipient returns `ok=false` without calling MailService (mock verify `times(0)`); `sendTest` with empty host returns specific "SMTP host is not configured" message; `sendTest` with mail exception returns `ok=false` with the exception message
- [x] 8.2 Update `AdminControllerIntegrationTest` (`src/test/java/com/ulp/admin/AdminControllerIntegrationTest.java`):
  - Rename test method `admin_settings_placeholder_renders` → `admin_settings_index_renders`
  - Keep the assertion `containsString("Cài đặt hệ thống")` (template `admin/settings.html` from task 7.2 must contain this string)
  - Add a new test `admin_settings_email_form_renders` that GETs `/admin/settings/email` as `admin@ulp.edu.vn`, expects 200, body contains masked password `********` and form field names
  - Add a new test `admin_settings_email_non_admin_forbidden` parameterized over `student@`, `lecturer@`, `head@` expecting 403 on GET `/admin/settings/email`
  - Add a new test `admin_settings_email_anonymous_redirects_login` expecting redirect to `/login` on GET `/admin/settings/email`
- [x] 8.3 Create `EmailSettingsControllerIntegrationTest` (`@SpringBootTest @AutoConfigureMockMvc`): `admin_save_valid_settings_redirects_with_success`; `admin_save_invalid_encryption_renders_form_with_error`; `admin_save_empty_password_preserves_existing_value` (set up DB row with known password, POST without password field, verify DB row unchanged); `admin_test_send_invalid_recipient_returns_400_or_json_error`; `admin_test_send_empty_host_returns_json_error_with_specific_message`
- [x] 8.4 Run `.\mvnw.cmd test` and confirm all green; if `AuthLoginIntegrationTest` or `Sprint1AuthIntegrationTest` fail, fix per task 4.5 _(verified — 114/114 tests pass)_ ← (verify: all 8 spec.md requirements have at least one corresponding test method; renamed `admin_settings_index_renders` passes against new template; coverage gap against spec.md scenarios is zero)

## 9. Documentation & decision record

- [x] 9.1 Create `docs/decisions/0008-smtp-password-plain-text.md` from `docs/templates/decision.md`: decision, alternatives (AES-256 with `TextEncryptor`, Vault, `is_secret` column flag), rationale (MVP scope), risks, mitigation (DB ACLs + masking in service layer + secret-key set), revisit trigger ("before production launch or 100+ active users")
- [x] 9.2 Update `CLAUDE.md` section 7 ("Admin Panel — hiện trạng"): change Settings row from "🚧 placeholder" to "✅ Email implemented; General/OAuth/AI placeholder"
- [x] 9.3 Update `CLAUDE.md` section 11 ("Things to NOT do"): add a note that SMTP credentials live in `system_settings` table, not properties files
- [x] 9.4 Document the changed `MailService` contract in `CLAUDE.md` section 5 ("Authentication & Authorization") — note that the bean is now always present and `send()` returns `false` instead of bean being absent ← (verify: decision record exists with revisit trigger; CLAUDE.md Settings row updated; example file no longer suggests editing SMTP in properties as the primary path)

## 10. End-to-end smoke (manual)

> Manual smoke; not in CI. Sub-tasks marked `← (verify: ...)` are automated by 8.2/8.3 — these manual checks are the "is everything actually wired" pass.

- [ ] 10.1 Boot the app locally with an empty `smtp.host` row; visit `/admin/settings/email` as ADMIN; confirm masked password and seeded defaults (host empty, port 587, encryption `tls`, from_name `ULP`)
- [ ] 10.2 Enter Mailtrap (or Gmail App Password) credentials; save; click "Send Test Email" to your own address; confirm UlpToast green and email arrives in inbox
- [ ] 10.3 Trigger forgot-password flow for a test user; confirm the reset email arrives via the configured SMTP
- [ ] 10.4 Stop the app; clear `smtp.host` via SQL `UPDATE system_settings SET setting_value = '' WHERE setting_key = 'smtp.host'`; restart; trigger forgot-password again; confirm reset link appears in console log (PasswordRecoveryService boolean-check fallback works)
- [ ] 10.5 Re-load `/admin/settings/email`; confirm password still masked (`********`), other values persisted
- [ ] 10.6 Save again WITHOUT typing in the password input (leave `********` placeholder verbatim); confirm forgot-password flow still works (i.e., empty-password-means-keep semantics holds end-to-end)
