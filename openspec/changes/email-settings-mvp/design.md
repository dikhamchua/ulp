## Context

ULP is a Spring Boot 3.4.4 / Thymeleaf SSR application. Authentication and
password recovery already depend on SMTP, but the SMTP credentials live in
`application-local.properties` and the `MailService` bean is only constructed
when `spring.mail.host` is present at startup (`@ConditionalOnProperty`).
Operators cannot edit SMTP without redeploying, and there is no admin UI for
any settings — `/admin/settings` is a placeholder.

The database schema already provides a `system_settings` key-value table with
rows seeded for the `SMTP` group (`smtp.host`, `smtp.port`, `smtp.username`,
`smtp.password`, `smtp.from_email`). The schema includes an `is_encrypted`
flag and an `updated_by` audit column. No code currently reads or writes this
table.

Stakeholders:

- **Admin operators** — need a single page to configure SMTP without server
  access.
- **Existing callers** (`PasswordRecoveryService` and any future notification
  sender) — must keep working without code changes.
- **Future maintainers** — must inherit a clear pattern for the rest of the
  Settings groups (General, OAuth, AI) that the schema already anticipates.

## Goals / Non-Goals

**Goals:**

- Provide a working `/admin/settings/email` page for `ADMIN` users to view,
  edit, and test SMTP transport + sender identity.
- Persist SMTP configuration in `system_settings` so changes take effect
  without restarting the application.
- Preserve the existing "no SMTP configured ⇒ password reset emails silently
  log instead of failing" behavior so dev workflow is unaffected.
- Establish the controller/service/repository pattern that subsequent settings
  groups (General, OAuth, AI) will reuse.
- Capture the plain-text-password trade-off in a durable decision record so
  it is not silently inherited by future agents.

**Non-Goals:**

- Encrypting `smtp.password` at rest. The accepted technical debt is
  documented and revisitable.
- Queueing or retrying email sends. Sends stay synchronous; failure surfaces
  immediately through the existing `boolean` return.
- A template editor, email logs table, bounce/open tracking, or delivery
  webhooks.
- Multi-provider transports (Resend, SendGrid, SES). SMTP only for MVP.
- Permission granularity finer than the `ADMIN` role. Project does not yet
  have a `feature_permissions` runtime lookup; that wiring is future work.
- Validating DKIM/SPF/DMARC, or sending a separate "test SMTP connection"
  request distinct from a real test email.

## Decisions

### Decision 1: DB-backed `JavaMailSender` instead of property-backed bean

**Choice**: Build a custom `MailSender` (`DbConfiguredMailSender`) that reads
SMTP settings from `system_settings` on every send and constructs a fresh
`JavaMailSenderImpl` per call. Replace the `@ConditionalOnProperty` gate on
`MailService` with an always-on bean that delegates to the DB-backed sender.

**Alternatives considered**:

- *Reload Spring context on save*: Tried mentally, rejected. Refreshing the
  application context invalidates request threads and is heavy for a settings
  save.
- *Write DB values back to `application-local.properties` and restart*:
  Rejected. Restart is operator-visible downtime and disk-level mutation of
  config files is hostile to containerized deploys.
- *Cache the `JavaMailSenderImpl` and invalidate on save*: Better than
  per-send instantiation, but adds cache-invalidation complexity for an MVP
  that sends few emails. Defer to a follow-up if profiling shows latency.

**Rationale**: Per-send instantiation costs a constructor and field
assignment — negligible compared to the network round-trip of the SMTP
handshake itself. Zero cache means zero stale-config bugs.

### Decision 2: Settings storage stays in `system_settings` (key-value)

**Choice**: Use the existing `system_settings` table. Email keys live under
`setting_group = 'SMTP'`. Add three new rows via Flyway: `smtp.encryption`,
`smtp.from_name`, `smtp.reply_to`.

**Alternatives considered**:

- *Typed `email_settings` table*: Cleaner schema, type-safe at the DB layer.
  Rejected because (a) the schema-owning team already chose key-value with
  `setting_group`, (b) the same table will host General/OAuth/AI groups, and
  duplicating that pattern would fork the architecture.

**Rationale**: Schema continuity outweighs type safety for a row count this
small. The service layer enforces types in Java.

### Decision 3: `smtp.password` stored plain text, masking via hardcoded key list

**Choice**: Store the SMTP password as plain text in
`system_settings.setting_value`. Masking on HTTP responses is enforced in
the service layer by a hardcoded set of secret keys
(`Set.of("smtp.password")`) — the GET response substitutes `********` for
any matching key before the form binds to the view. **No new column** is
added to `system_settings`; the existing `is_encrypted` column is left
alone and unused for the masking decision.

**Alternatives considered**:

- *AES-256 with app-key*: ~30 min of work via Spring's `TextEncryptor`.
  Recommended by the agent; explicitly declined by the user for MVP scope.
- *Add `is_secret` column via migration and let the entity read it*: More
  generic but introduces a schema change for a single row; the hardcoded
  set is simpler and equally explicit.
- *Spring Cloud Vault / Secret Manager*: Over-engineered for a capstone.

**Rationale**: User decision on plain-text. The trade-off is documented in
`docs/decisions/0008-smtp-password-plain-text.md` with a named revisit
trigger ("before production launch or 100+ active users") so it is not
inherited silently. Service-layer hardcoding for masking keeps the schema
clean; the secret key list lives next to its only consumer.

### Decision 4: Empty password input on save means "keep current"

**Choice**: When the admin submits the form with an empty `smtp.password`
field, the service reads the existing value from DB and leaves it unchanged.
Only a non-empty submission overwrites.

**Alternatives considered**:

- *Require password every save*: Friction; admin who edits "from name" should
  not need to re-enter SMTP password.
- *Use a separate "change password" checkbox*: Extra UI for the common case.

**Rationale**: Matches the masking on GET (admin never sees the real value,
so they cannot copy it back). Standard pattern across admin tools.

### Decision 5: Admin role check at controller level only

**Choice**: Use `@PreAuthorize("hasRole('ADMIN')")` at the controller class
level. Do not introduce a `manage_email_settings` permission row.

**Alternatives considered**:

- *RBAC permission via `feature_permissions` table*: The schema supports it,
  but no other feature reads from that table at runtime yet. Adding the
  first lookup here would create an unevenly-applied pattern.

**Rationale**: Keep the permission story consistent with the rest of `/admin`
URLs. When `feature_permissions` lookup ships project-wide, this is a 1-line
swap.

### Decision 6: Test send uses a fixed message body

**Choice**: The "Send Test Email" action posts only a recipient address. The
backend constructs a fixed subject ("ULP — SMTP test email") and body
("This is a test email from ULP. If you received this, your SMTP
configuration works.") The frontend shows a toast: green on success, red
with the SMTP error message on failure.

**Alternatives considered**:

- *Let admin write the test subject/body*: Trivial cost, but the goal is to
  verify transport, not to ad-hoc compose. Skip.

**Rationale**: Smallest surface that proves transport works.

### Decision 7: `EmailSettingsService.save()` is `@Transactional`

**Choice**: Annotate `save()` with `@Transactional`. All `smtp.*` row upserts
happen inside the same transaction, including the optional skip of the
password row. Validation runs BEFORE entering the transaction (in the
controller's binding-result check) so a validation failure never opens a
transaction.

**Alternatives considered**:

- *No transaction; rely on per-row atomicity*: Rejected because the spec
  scenarios for validation-fail explicitly require "does not modify any
  `system_settings` rows". A partial commit would violate the contract if
  upsert #3 fails for any reason (constraint, DB hiccup).

**Rationale**: Spec contract demands all-or-nothing on save. Spring's
`@Transactional` is the lightest tool that delivers it.

### Decision 8: CSRF token surfaced via meta tags in `fragments/head.html`

**Choice**: Add two meta tags to the shared `head.html` fragment:

```html
<meta name="_csrf" th:content="${_csrf.token}"/>
<meta name="_csrf_header" th:content="${_csrf.headerName}"/>
```

The AJAX test-send endpoint reads these tags and includes the token as a
request header (`X-CSRF-TOKEN`). Spring Security's default CSRF chain stays
on; nothing in `SecurityConfig` changes.

**Alternatives considered**:

- *Disable CSRF for `/admin/settings/email/test`*: Reduces the project's
  attack surface uniformity. Rejected.
- *Use `CookieCsrfTokenRepository` so JS reads from cookie*: Larger change
  with side effects on form login. Rejected for MVP.
- *Make the test-send a regular form POST instead of AJAX*: Acceptable but
  removes the toast UX (full page reload). Rejected because the toast is the
  feedback channel.

**Rationale**: Meta tags are the standard Spring Security + jQuery / fetch
pattern. Adding them to the shared fragment unlocks future admin AJAX
endpoints (e.g., user moderation actions in Sprint 3+).

### Decision 9: `PasswordRecoveryService` switches from null-bean to boolean-return detection

**Choice**: Refactor `PasswordRecoveryService` to constructor-inject
`MailService` (now always present) and decide the "log the reset link to
console" fallback by checking the boolean return of `send()` instead of by
checking whether the bean is null.

Before:

```java
@Autowired(required = false)
private MailService mailService;
...
if (mailService != null) { mailService.send(...); }
else { log.info("Mail not configured ... Token: {}", link); }
```

After:

```java
private final MailService mailService;
public PasswordRecoveryService(..., MailService mailService) { ... }
...
boolean sent = mailService.send(user.getEmail(), subject, body);
if (!sent) {
    log.info("Mail not configured or send failed — token created for {} "
           + "but email NOT sent. Link: {}", user.getEmail(), link);
}
```

**Rationale**: Removing `@ConditionalOnProperty` from `MailService` would
turn the existing null-check into permanently-true (bean always present),
collapsing the else-branch into dead code and silently losing the
"developer sees reset link in console" workflow when SMTP is empty. The
boolean-return path keeps the workflow alive AND covers a new failure mode
(SMTP configured but unreachable) with the same console fallback.

## Architecture sketch

```
┌────────────────────────────────────────────────────────────────────┐
│ Browser (admin/settings-email.html + admin-settings.js)            │
└──────────┬─────────────────────────────────────┬───────────────────┘
           │ GET /admin/settings/email           │ POST .../test
           │ POST /admin/settings/email          │
           ▼                                     ▼
┌────────────────────────────────────────────────────────────────────┐
│ EmailSettingsController  @PreAuthorize hasRole(ADMIN)              │
│  - GET  : load() → form view                                       │
│  - POST : save() → 302 back to form + flash toast                  │
│  - POST /test : sendTest() → JSON { ok, error? }                   │
└──────────┬─────────────────────────────────────┬───────────────────┘
           ▼                                     ▼
┌────────────────────────────────────────────┐  ┌────────────────────┐
│ EmailSettingsService                       │  │ MailService        │
│  - load()        → EmailSettingsDto        │──│  send(to,subj,body)│
│  - save(dto, currentUser)                  │  │  → boolean         │
│  - sendTest(to)  → TestResult              │  └──────────┬─────────┘
└──────────┬─────────────────────────────────┘             │
           ▼                                               ▼
┌────────────────────────────────────────────┐  ┌────────────────────────┐
│ SystemSettingsRepository (JPA)             │  │ DbConfiguredMailSender │
│  Map<String,String> findByGroup("SMTP")    │←─│  reads SMTP rows,      │
│  upsert(key,value,updatedBy)               │  │  builds JavaMailSender,│
└──────────┬─────────────────────────────────┘  │  sends, returns bool   │
           ▼                                    └────────────────────────┘
       MySQL system_settings table
```

## Data model delta

Existing rows seeded in `V1__init_schema.sql`:

| `setting_key`       | group | notes                         |
|---------------------|-------|-------------------------------|
| `smtp.host`         | SMTP  |                               |
| `smtp.port`         | SMTP  |                               |
| `smtp.username`     | SMTP  |                               |
| `smtp.password`     | SMTP  | `is_encrypted=0` (default)    |
| `smtp.from_email`   | SMTP  |                               |

Masking on GET is enforced at the service layer by a hardcoded set
(`SECRET_KEYS = Set.of("smtp.password")`), not by a DB column flag. The
existing `is_encrypted` column is unused for masking and stays `0` for all
SMTP rows.

New rows in `V9__seed_email_settings_extras.sql`:

| `setting_key`       | default | description                       |
|---------------------|---------|-----------------------------------|
| `smtp.encryption`   | `tls`   | one of `none\|tls\|ssl`            |
| `smtp.from_name`    | `ULP`   | Display name in the From header   |
| `smtp.reply_to`     | (empty) | Optional reply-to address         |

Migration uses `INSERT ... ON DUPLICATE KEY UPDATE setting_value = setting_value`
(no-op on conflict) so V9 is fully idempotent. No `ALTER TABLE`, no `UPDATE`
on existing rows.

## API surface

```
GET   /admin/settings                  Settings index (lists groups)
GET   /admin/settings/email            Email form view
POST  /admin/settings/email            Save email settings (form-urlencoded)
POST  /admin/settings/email/test       Send test email (form-urlencoded; AJAX)
```

POST `/test` request body:
```
testRecipient=admin@example.com
```

POST `/test` JSON response:
```json
{ "ok": true }
{ "ok": false, "error": "Authentication failed: 535" }
```

## Risks / Trade-offs

- **Plain-text password in DB** → Mitigated by documenting in
  `docs/decisions/0008-smtp-password-plain-text.md` with explicit revisit
  trigger; UI masking prevents accidental display; secret-key list lives
  next to its only consumer.
- **Per-send `JavaMailSenderImpl` construction** → Negligible compared to
  SMTP RTT; revisit only if profiling shows a hotspot.
- **No connection pooling** → Spring's `JavaMailSenderImpl` opens a new SMTP
  connection per send. For MVP volume (password reset, occasional admin
  email) this is fine. If sending volume grows, swap to a transport pool.
- **`smtp.password` empty on first install** → Service treats empty as "not
  configured"; `send()` short-circuits to `false`. Refactored
  `PasswordRecoveryService` then logs the reset link to the console,
  preserving the existing dev workflow.
- **Test send blocks request thread** → Acceptable for admin-initiated
  action. Configure `JavaMailSenderImpl` with short timeouts (10000ms each)
  using property keys `mail.smtp.connectiontimeout` and `mail.smtp.timeout`
  (lowercase, per JavaMail spec; camelCase variants are silently ignored).
- **CSRF on AJAX test-send** → Mitigated by adding `<meta name="_csrf">`
  and `<meta name="_csrf_header">` to `fragments/head.html` (Decision 8).
  Fetch sends the value as `X-CSRF-TOKEN` header. Spring Security's default
  CSRF chain stays on.
- **`MailService` becomes unconditional** → Removes the
  `@ConditionalOnProperty` gate. Existing
  `AuthLoginIntegrationTest`, `Sprint1AuthIntegrationTest`, and
  `AdminControllerIntegrationTest` must be audited:
  `AdminControllerIntegrationTest.admin_settings_placeholder_renders` will
  fail after `/admin/settings` stops being a placeholder; this is a
  required rename, not an incidental break.
- **Concurrent admin saves** → MySQL `INSERT ... ON DUPLICATE KEY UPDATE`
  is atomic per row. Across rows, two admins saving simultaneously could
  produce an interleaved final state (admin A's host + admin B's port).
  Accepted for MVP because (a) `ADMIN` is single-digit headcount in a
  capstone project, (b) the worst case is a corrupted SMTP config that the
  admin notices on the next test send and re-saves.
- **Flyway V9 applied while app is mid-request** → V9 only INSERTs three
  new rows; no schema mutation, no DML on existing rows. A request reading
  the SMTP group concurrently either sees the old map (without the three
  new keys) or the new map. Defaults already exist in the service layer
  for missing keys (encryption="none", from_name="", reply_to=""), so the
  worst-case mid-migration read still produces a valid (degraded) From
  header. Acceptable.

## Migration plan

1. Deploy migration `V9__seed_email_settings_extras.sql` (INSERT-only,
   fully idempotent via `ON DUPLICATE KEY UPDATE setting_value = setting_value`).
2. Deploy code with `DbConfiguredMailSender` shipped, `MailService`
   unconditional, and `PasswordRecoveryService` refactored to boolean-return
   check. Existing `application-local.properties` SMTP values stop being
   read at runtime; admins must populate the DB rows via
   `/admin/settings/email`. Document migration note in release CHANGELOG.
3. **Rollback**: revert the code change; the migration leaves harmless extra
   rows in `system_settings` and does not need to be reverted. The
   conditional `MailService` resumes reading from properties after revert.

## Open questions

- Should the test-send recipient be free-form, or default to the currently
  logged-in admin's email? **Resolved**: free-form, but pre-fill with the
  logged-in user's email for convenience.
- Do we add a "last test result" indicator on the Settings index? **Out of
  scope for MVP** — no logs table per Decision/scope.
- Where does `smtp.from_email` end up in headers when `smtp.from_name` is
  blank? **Behavior**: send with bare email address (`<noreply@ulp.edu.vn>`)
  rather than `null <…>`. Implementation note for the service.
