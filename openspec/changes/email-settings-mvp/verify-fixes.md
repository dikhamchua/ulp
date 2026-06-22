# Verify Fixes — email-settings-mvp

Append-only log of issues fixed in response to verifier reports. New entries
go at the bottom. Each entry records the verifier that flagged the issue,
the original finding, and the semantic resolution.

## [2026-06-22] Round 1 (from spx-apply auto-verify)

### spx-verifier

- **W2 (missing test for atomic save scenario)**: Added
  `save_method_is_transactional` test in `EmailSettingsServiceTest` that
  reflectively verifies the `@Transactional` annotation is present on
  `EmailSettingsService.save()` and is not read-only. Spec scenario "Save
  is atomic across all rows" now has verifiable test coverage at the
  annotation level (Spring's transaction proxy handles the rollback
  behavior — integration test would require a contrived row-level
  constraint failure which is brittle).
- **W3 (missing tests for From header formatting)**: Created
  `DbConfiguredMailSenderTest` with 5 tests covering the From header
  scenarios — display name set when `from_name` non-empty, bare email when
  `from_name` empty, Reply-To included when configured, empty-host
  short-circuit. Uses reflection to call private `buildMessage()` so tests
  inspect headers without network I/O.
- **W4 (flash key not asserted in test)**: Acknowledged — kept current
  behavior because the controller key (`flashSuccess`) and template
  binding (`${flashSuccess}`) already match. Adding a flash-attribute
  assertion to the integration test would not catch a future drift unless
  paired with a render assertion; left for follow-up.
- **W5 (tearDown only restored 2 rows)**: Rewrote
  `EmailSettingsControllerIntegrationTest.setUp/tearDown` to back up and
  restore the entire SMTP setting group, eliminating cross-test
  contamination risk regardless of test execution order.
- **W6 (task spec said to add tests to AdminControllerIntegrationTest)**:
  Acknowledged as a task-description deviation, not a coverage gap. The
  three test cases (admin form renders, non-admin forbidden, anonymous
  redirects) are present in `EmailSettingsControllerIntegrationTest`. No
  duplicate added.
- **S6 (encoding "Khong xac dinh" without diacritics)**: Fixed to "Không
  xác định" in `admin-settings.js` so the fallback message matches the
  Vietnamese-with-diacritics convention used elsewhere in the UI.

### spx-arch-verifier

- **DIP leak: `DbConfiguredMailSender.SendResult` imported by admin layer**:
  Extracted the result type into a new top-level record
  `com.ulp.shared.mail.MailSendResult` with `success()` and
  `failure(message)` factory methods. `MailService.sendWithDetail()` now
  returns `MailSendResult`. `EmailSettingsService` imports
  `MailSendResult` only — no longer references the transport
  implementation class.
- **Entity `@Setter` overly permissive on `settingKey`/`settingGroup`**:
  Removed `@Setter` from both fields in `SystemSetting`. These are
  immutable after creation (unique key + group identity) and the
  three-arg constructor sets them. Mutating them post-persist could
  corrupt the unique constraint.
- **Entity `@NoArgsConstructor` missing `access = PROTECTED`**: Added
  `(access = AccessLevel.PROTECTED)` to match the convention enforced on
  `User`, `ClassEntity`, and other JPA entities in the project.

## [2026-06-22] Round 2 (from spx-verify)

### spx-uiux-verifier

- **C2 (field errors missing aria-describedby + role="alert")**: Rewrote
  every field error in `settings-email.html` to use `<span id="X-error"
  class="field-error" role="alert">` paired with conditional
  `aria-describedby="X-error"` and `aria-invalid` on the input. Pattern
  mirrors `classes/form.html`. Affects host/port/encryption/username/
  fromName/fromEmail/replyTo.
- **C3 (required / aria-required missing)**: Added `required` and
  `aria-required="true"` on host, port, username, fromName, fromEmail.
  password kept optional (empty = keep current). Added `maxlength` where
  the DTO enforces a length constraint.
- **C4 (encryption radio group not in fieldset/legend)**: Wrapped the
  three radio buttons in `<fieldset class="radio-group" aria-required>
  <legend class="sr-only">Encryption</legend>...` and changed the visible
  label to a `<span class="form-row-label">`. Added `aria-invalid` /
  `aria-describedby` on the fieldset itself.
- **W1 (no aria-live region for test send result)**: Added
  `<div id="testSendResult" class="sr-only" role="status"
  aria-live="polite">` to `settings-email.html`. Updated
  `admin-settings.js` to mirror toast text into the live region via an
  `announce()` helper (clear-then-set pattern with 50ms delay so SR
  retriggers).
- **W2 (flash success alert missing role)**: Added `role="status"` to
  the `<div class="alert alert-success">` so SR announces it after
  redirect.
- **W3 (disabled cards missing aria-disabled)**: Added
  `aria-disabled="true"` to both placeholder cards in `settings.html`.
- **W4 (test send button has no aria-controls)**: Added
  `aria-controls="testRecipient testSendResult"` so SR knows the button
  reads from one element and writes to another.
- **W5 (focus-visible missing on buttons/links)**: Added CSS rules for
  `.btn-primary:focus-visible`, `.btn-ghost:focus-visible`,
  `.settings-card:focus-visible` with 2px primary outline + 2px offset.
- **W6 (test send button not full-width on mobile)**: Added
  `.test-send-row button { width: 100%; }` inside the existing 720px
  media query.
- **W7 (breadcrumb missing aria)**: Added `aria-label="Breadcrumb"` on
  `<nav>` and `aria-current="page"` on the current crumb.
- **W8 (field-error using `<p>` instead of `<span>`)**: Resolved as part
  of C2 — all errors are now `<span>` matching `classes/form.html`.
- **S1 (autocomplete missing)**: Added `autocomplete="email"` on
  fromEmail, replyTo, testRecipient. `autocomplete="off"` kept on host
  and username.
- **S3 (port input lacks inputmode)**: Added `inputmode="numeric"` on
  port input for better mobile keyboard.
- **S4 (no empty state for fresh install)**: Added a conditional
  `<div class="alert alert-info">` that renders when `form.host` is null
  or blank, prompting the admin to configure SMTP. Added `.alert-info`
  CSS rule.
- **S6 (breadcrumb separator should be aria-hidden)**: Added
  `aria-hidden="true"` on the `<span class="sep">/</span>`.

### spx-test-verifier

- **C5 (port boundary tests missing)**: Added three integration tests in
  `EmailSettingsControllerIntegrationTest`:
  `save_port_zero_renders_form_with_error`,
  `save_port_out_of_range_renders_form_with_error`,
  `save_port_non_numeric_returns_type_mismatch_error`. Each verifies the
  form re-renders without mutating DB rows.
- **C6 (fromEmail/host/replyTo validation tests missing)**: Added four
  integration tests covering `save_empty_host_renders_form_with_error`,
  `save_invalid_from_email_renders_form_with_error`,
  `save_invalid_reply_to_renders_form_with_error`, and
  `save_empty_reply_to_is_accepted` (positive case to confirm optional
  field passes).
- **W8 (brittle `times(7)` assertions)**: Replaced `verify(repository,
  times(7))` and `times(8)` with `verify(repository, atLeastOnce())` in
  `EmailSettingsServiceTest` save tests. The interesting assertion was
  always the content of the saved rows (via `ArgumentCaptor`), not the
  count. Removed unused `Mockito.times` import.

### spx-arch-verifier (Round 2)

- **W4 (`loadGroupAsMap` as default method on JPA repo)**: Acknowledged.
  Method is 4 lines, purely a transformation, and reuses across other
  settings groups will benefit from a single canonical implementation.
  Kept on the repository interface for now; revisit when General/OAuth
  settings tabs ship.
- **W5 (`DbConfiguredMailSender` cross-coupling in `shared/`)**:
  Acknowledged as MVP-acceptable cross-coupling within the same `shared/`
  parent package. Both modules are owned by the platform layer and the
  coupling is unidirectional. Will revisit if `shared/mail` ever needs
  to be extracted as a library.
- **W2 (`@Transactional(readOnly = true)` on `sendTest()`)**: Removed.
  Method delegates to `mailService.sendWithDetail` which manages its own
  repository call; no outer transaction needed.
- **S2 (`@JsonInclude(NON_NULL)` missing on `TestResult`)**: Added
  `@JsonInclude(JsonInclude.Include.NON_NULL)` on the
  `TestResult` record so `ok=true` responses serialise to
  `{"ok":true}` per spec, not `{"ok":true,"error":null}`.
- **S8 (`MASKED` constant duplicated in 3 places)**: Promoted
  `MASKED = "********"` to a public constant on `EmailSettingsDtos`.
  Service and unit test now reference `EmailSettingsDtos.MASKED`.
  Integration test still uses a local constant (different package, kept
  for test readability).

### spx-verifier (Round 2)

- **C1 (`SystemSetting.updatedAt` Hibernate `updatable = false`)**:
  Added a Javadoc comment on the field explaining that MySQL's
  `ON UPDATE CURRENT_TIMESTAMP` (from V1 schema) satisfies the spec's
  "set updated_at to current timestamp" requirement at the DB layer.
  Hibernate intentionally does not write to the column.
- **W7 (Vietnamese error messages without diacritics)**: Fixed in
  `EmailSettingsService.sendTest()` ("Vui long nhap email nguoi nhan"
  → "Vui lòng nhập email người nhận"; "Email nguoi nhan khong hop le"
  → "Email người nhận không hợp lệ") and in all Bean Validation
  `message` attributes on `EmailSettingsForm` (Host/Port/Encryption/
  Username/From Name/From Email/Reply-To). Updated the two affected
  test assertions to match the new diacritic-correct strings.

## [2026-06-22] Round 3 (from spx-verify re-check)

### spx-uiux-verifier / spx-verifier

- **W1 (aria-invalid on `<fieldset>` not valid per ARIA in HTML)**:
  Switched the encryption radio container from `<fieldset class="radio-group">
  <legend class="sr-only">` to `<div class="radio-group" role="radiogroup"
  aria-labelledby="encryption-label">`. The visible label is now a
  `<span id="encryption-label" class="form-row-label">` referenced via
  `aria-labelledby`. `aria-invalid` and `aria-describedby` are valid on
  `role="radiogroup"` and now reach NVDA/JAWS correctly. Removed the
  now-unused `.radio-group legend.sr-only` CSS rule and the duplicate
  fieldset reset block in `admin.css`.

### spx-test-verifier

- **W2 (`admin_settings_index_renders` assertion too loose)**: Added a
  third `content().string(containsString("/admin/settings/email"))`
  assertion so the test verifies that the Email entry actually links to
  the form page, not just renders the text. Aligns with the spec
  requirement "renders a page listing at least an Email entry linking
  to `/admin/settings/email`".

### spx-verifier

- **W3 (JS HTTP guard has redundant `&& status !== 200` + no JSON
  content-type check)**: Simplified the HTTP error guard to
  `if (!response.ok)` (the extra status check was dead). Added a
  `Content-Type` check before `response.json()` so a session-expired
  redirect (Spring serving the login HTML on 200) surfaces as a friendly
  toast "Phản hồi không phải JSON (phiên đăng nhập có thể đã hết hạn)"
  instead of a cryptic `SyntaxError` from `response.json()`.
