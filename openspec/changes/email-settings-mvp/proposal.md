## Why

The Admin panel ships a `/admin/settings` placeholder, and SMTP configuration
currently lives in `application-local.properties`. Operators cannot change the
mail transport without redeploying the app, and the `MailService` bean is only
created when properties are present at startup. The schema already provisions
`system_settings` rows for the `SMTP` group, so the foundation exists but no
UI or runtime wiring consumes it. This change delivers the first real admin
Settings tab — Email — so a privileged user can configure SMTP transport,
sender identity, and verify it with a test send.

## What Changes

- Add an `Email` settings page at `/admin/settings/email`, accessible to users
  with role `ADMIN`.
- Read and write the SMTP-group rows in `system_settings` through a typed
  service layer keyed by `smtp.*` setting keys.
- Replace the placeholder handler for `/admin/settings` with a real handler
  that lists setting groups (only `Email` for MVP) and links into the Email
  detail page.
- Introduce three new setting keys: `smtp.encryption`, `smtp.from_name`,
  `smtp.reply_to`. Seed defaults via a Flyway migration so the form has rows
  to bind on first load.
- Wire a `DbConfiguredMailSender` bean that reads SMTP credentials from
  `system_settings` at send time and bypasses the `@ConditionalOnProperty`
  gate on `MailService`. When `smtp.host` is empty, `send()` returns `false`
  and logs a warning (no exception).
- Add a `Send Test Email` action: the form posts a recipient address, the
  backend triggers a fixed test message, and the UI shows a toast notification
  (success or failure with the SMTP error message).
- Mask the password field on GET responses. The service layer maintains a
  hardcoded list of secret setting keys (currently only `smtp.password`) and
  substitutes the literal string `********` for their values before
  serialising the form to the view. On save, an empty password input OR a
  submission equal to the masked placeholder both mean "keep current
  password" — only a non-empty, non-masked value overwrites.
- **BREAKING**: `MailService` no longer requires `spring.mail.host` to be set
  at startup. The bean is unconditional but `send()` returns `false` when the
  DB-backed transport is not configured. `PasswordRecoveryService` is updated
  from null-bean detection (`@Autowired(required=false)` + `mailService != null`)
  to boolean-return detection (`mailService.send(...)` returns `false` ⇒
  treat as "not configured" and log the reset link to the console), so the
  existing dev workflow ("no SMTP configured ⇒ link visible in logs") is
  preserved.
- Record a decision (`docs/decisions/0008-smtp-password-plain-text.md`)
  capturing the accepted risk that `smtp.password` is stored plain text in
  `system_settings.setting_value`. Mark as MVP technical debt with a revisit
  trigger ("before production launch or 100+ active users").

## Capabilities

### New Capabilities

- `admin-settings-email`: Admin-only management of SMTP transport
  configuration and sender identity, with read/write/test-send operations
  backed by the `system_settings` table.

### Modified Capabilities

<!-- none — admin-settings-email is the first capability in this area -->

## Impact

- **Schema (Flyway)**: new migration `V9__seed_email_settings_extras.sql`
  inserts three new `system_settings` rows (`smtp.encryption`,
  `smtp.from_name`, `smtp.reply_to`). No new columns are added; the existing
  schema has only `is_encrypted` (not `is_secret`) and masking is enforced in
  the service layer by hardcoded key list, not by a DB flag.
- **Code (new packages)**:
  - `com.ulp.admin.settings.controller.EmailSettingsController`
  - `com.ulp.admin.settings.service.EmailSettingsService`
  - `com.ulp.admin.settings.dto.EmailSettingsDtos`
  - `com.ulp.shared.settings.SystemSettingsRepository`
  - `com.ulp.shared.settings.entity.SystemSetting`
  - `com.ulp.shared.mail.DbConfiguredMailSender` (replaces conditional
    `MailService`)
- **Code (modified)**:
  - `AdminController.placeholder()` — drop `settings` from the placeholder
    set; add real `/admin/settings` + `/admin/settings/email` handlers.
  - `MailService` — bean is now unconditional and delegates to
    `DbConfiguredMailSender`.
  - `PasswordRecoveryService` — replace `@Autowired(required=false)` +
    null-check on `MailService` with constructor injection + boolean-return
    check on `send()`, preserving the "log reset link when SMTP is empty"
    behavior.
  - `fragments/head.html` — add `<meta name="_csrf">` and
    `<meta name="_csrf_header">` tags so the AJAX test-send endpoint can
    submit the CSRF token (Spring Security default chain enforces CSRF on
    POST).
- **Templates**: `admin/settings.html` (index), `admin/settings-email.html`
  (form), updates to `fragments/admin-sidebar.html` if any.
- **Static assets**: `static/js/admin-settings.js` for the test-send AJAX +
  toast (reuses `window.UlpToast` defined in `static/js/app.js`); small
  additions to `static/css/admin.css` for form layout.
- **Tests**: new `EmailSettingsControllerIntegrationTest` and
  `EmailSettingsServiceTest`. `AdminControllerIntegrationTest` updated:
  rename `admin_settings_placeholder_renders` → `admin_settings_index_renders`
  and adjust assertion to match the new index template content (it will no
  longer hit the placeholder view). `MailServiceTest` updated to cover the
  DB-backed path (sender missing, sender configured, send failure).
- **Docs**: new decision record `0008-smtp-password-plain-text.md`; small
  edits to `CLAUDE.md` admin section once Settings is no longer placeholder.
- **Out of scope**: queue/retry, template editor, email logs, multi-provider
  (Resend/SendGrid/SES), DKIM/SPF/DMARC validation, password encryption,
  per-permission RBAC (uses role-only check).
