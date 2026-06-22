## ADDED Requirements

### Requirement: Admin-only access to Email Settings page

The system SHALL restrict access to the Email Settings page and its actions
(view, save, test send) to authenticated users holding the `ADMIN` role.

#### Scenario: Anonymous user accesses the Email Settings page
- **WHEN** an unauthenticated user requests `GET /admin/settings/email`
- **THEN** the system redirects the request to `/login`
- **AND** does not expose any SMTP configuration values in the response

#### Scenario: Authenticated non-admin user accesses the Email Settings page
- **WHEN** a user authenticated as `STUDENT`, `LECTURER`, or `HEAD` requests
  `GET /admin/settings/email`
- **THEN** the system responds with HTTP 403 (or the project's standard
  forbidden view)
- **AND** does not expose any SMTP configuration values in the response

#### Scenario: Admin user accesses the Email Settings page
- **WHEN** a user authenticated as `ADMIN` requests `GET /admin/settings/email`
- **THEN** the system responds with HTTP 200 and renders the form view
  pre-filled with current setting values (password rendered as `********`)

### Requirement: Read Email Settings from system_settings

The system SHALL load all Email-group settings from the `system_settings`
table keyed by `smtp.*` keys when rendering the Email Settings form.

#### Scenario: Form load with seeded settings
- **WHEN** an admin loads `GET /admin/settings/email`
- **THEN** the form fields display values read from rows where
  `setting_group = 'SMTP'`, mapping `smtp.host`, `smtp.port`,
  `smtp.encryption`, `smtp.username`, `smtp.from_name`, `smtp.from_email`,
  `smtp.reply_to` to their respective inputs
- **AND** the `smtp.password` field is rendered as the literal string
  `********` so the real value is never sent to the browser

#### Scenario: Form load when an optional setting row is missing
- **WHEN** a non-required `smtp.*` row (`reply_to`, `from_name`) is absent
  from `system_settings`
- **THEN** the corresponding form input renders empty
- **AND** the page still loads with HTTP 200

### Requirement: Save Email Settings to system_settings

The system SHALL persist submitted Email Settings values to the
`system_settings` table on form submission, recording the submitting admin
as `updated_by`. All row writes for a single save MUST occur inside a
single database transaction.

#### Scenario: Admin saves valid settings
- **WHEN** an admin submits `POST /admin/settings/email` with valid values
  for host, port, encryption, username, from_email, from_name, and a
  non-empty password
- **THEN** the system upserts each `smtp.*` row with the submitted value
- **AND** sets `updated_by` to the admin's user id and `updated_at` to the
  current timestamp on every modified row
- **AND** redirects to `GET /admin/settings/email` with a flash success
  message

#### Scenario: Save is atomic across all rows
- **WHEN** an admin submits a valid form
- **AND** the upsert of any single row fails for a database-level reason
  (constraint violation, connection drop, deadlock)
- **THEN** the system rolls back ALL row writes in this save call
- **AND** the request surfaces a server error or re-renders the form with
  an error
- **AND** no `smtp.*` row in `system_settings` reflects the partial state

#### Scenario: Admin saves with empty password field
- **WHEN** an admin submits the form with the password input left empty,
  OR with the masked placeholder `********` submitted verbatim
- **THEN** the system keeps the existing `smtp.password` row value unchanged
- **AND** still upserts the remaining submitted fields normally

#### Scenario: Admin submits invalid host
- **WHEN** an admin submits the form with `smtp.host` empty
- **THEN** the system responds with HTTP 200 (or the project's standard
  validation re-render status)
- **AND** does not modify any `system_settings` rows
- **AND** the response includes an inline error message indicating the host
  is required

#### Scenario: Admin submits port out of range
- **WHEN** an admin submits the form with `smtp.port` set to a value outside
  `1..65535` or non-numeric
- **THEN** the system rejects the save with an inline error on the port
  field
- **AND** does not modify any `system_settings` rows

#### Scenario: Admin submits an invalid encryption value
- **WHEN** an admin submits `smtp.encryption` set to a value outside
  `{none, tls, ssl}`
- **THEN** the system rejects the save with an inline error on the
  encryption field
- **AND** does not modify any `system_settings` rows

#### Scenario: Admin submits invalid from_email
- **WHEN** an admin submits `smtp.from_email` with a value that does not
  parse as an RFC-5322 email address
- **THEN** the system rejects the save with an inline error on the from_email
  field
- **AND** does not modify any `system_settings` rows

#### Scenario: Admin submits invalid reply_to but reply_to is optional
- **WHEN** an admin submits `smtp.reply_to` empty
- **THEN** the system saves the empty value without raising a validation
  error

#### Scenario: Admin submits non-empty invalid reply_to
- **WHEN** an admin submits `smtp.reply_to` with a non-empty value that does
  not parse as an email address
- **THEN** the system rejects the save with an inline error on the reply_to
  field
- **AND** does not modify any `system_settings` rows

### Requirement: Send Test Email action

The system SHALL provide a Send Test Email action that constructs and sends
a fixed test message using the current persisted SMTP configuration.

#### Scenario: Test send with valid configuration
- **WHEN** an admin posts `POST /admin/settings/email/test` with a valid
  recipient email address
- **AND** the persisted SMTP configuration is sufficient to authenticate
  with the SMTP server
- **THEN** the system constructs an email with subject "ULP — SMTP test
  email" and a fixed body
- **AND** sends it via the DB-backed mail transport
- **AND** responds with JSON `{ "ok": true }`

#### Scenario: Test send to invalid recipient address
- **WHEN** an admin posts the test action with `testRecipient` empty or not
  parsable as an email address
- **THEN** the system responds with JSON
  `{ "ok": false, "error": "<validation message>" }`
- **AND** does not attempt to send

#### Scenario: Test send when SMTP host is empty
- **WHEN** an admin posts the test action while `smtp.host` is empty in
  `system_settings`
- **THEN** the system responds with JSON
  `{ "ok": false, "error": "SMTP host is not configured" }`
- **AND** does not attempt to send

#### Scenario: Test send when SMTP credentials are wrong
- **WHEN** an admin posts the test action and the SMTP server rejects
  authentication
- **THEN** the system catches the underlying mail exception
- **AND** responds with JSON
  `{ "ok": false, "error": "<message from the mail exception>" }`
- **AND** does not modify any `system_settings` rows

### Requirement: DB-backed mail transport

The system SHALL deliver outgoing email by reading the current SMTP
configuration from `system_settings` at send time, without requiring
`spring.mail.*` properties at application startup.

#### Scenario: Send call when SMTP host is configured in DB
- **WHEN** any application component calls the mail service to send a
  message
- **AND** `smtp.host` and other required `smtp.*` rows hold non-empty
  values
- **THEN** the system constructs a `JavaMailSender` from the DB values for
  this send
- **AND** dispatches the message
- **AND** returns `true` on success or `false` on a caught mail exception
  (and logs the failure)

#### Scenario: Send call when SMTP host is empty
- **WHEN** any application component calls the mail service to send a
  message
- **AND** `smtp.host` is empty or absent in `system_settings`
- **THEN** the system does not attempt a network call
- **AND** logs a warning that SMTP is not configured
- **AND** returns `false` so the caller can fall back (e.g., log the
  password-reset link to the console)

#### Scenario: Send uses from_name when present
- **WHEN** the system sends any email
- **AND** `smtp.from_name` is non-empty in `system_settings`
- **THEN** the From header is formatted as
  `"<smtp.from_name>" <smtp.from_email>`

#### Scenario: Send uses bare from_email when from_name is empty
- **WHEN** the system sends any email
- **AND** `smtp.from_name` is empty in `system_settings`
- **THEN** the From header is set to the value of `smtp.from_email` alone

#### Scenario: Send includes reply-to when configured
- **WHEN** the system sends any email
- **AND** `smtp.reply_to` is non-empty
- **THEN** the message includes a Reply-To header set to that value

### Requirement: Mask secret settings in HTTP responses

The system SHALL never include the plain-text value of any setting key
listed in the service layer's secret-key set (currently
`{"smtp.password"}`) in the response body of admin endpoints.

#### Scenario: Password is masked on form load
- **WHEN** an admin loads `GET /admin/settings/email`
- **THEN** the rendered HTML contains the literal string `********` in the
  password input value
- **AND** does not contain the actual password value anywhere in the
  response

#### Scenario: Password is preserved when admin saves without entering it
- **WHEN** an admin saves the form with the password input left empty (i.e.
  the masked placeholder cleared, OR the masked placeholder `********`
  submitted verbatim)
- **THEN** the persisted `smtp.password` value is unchanged from before the
  save

### Requirement: Settings index page lists Email group

The system SHALL render a Settings index at `/admin/settings` that links
into the available settings groups, including Email.

#### Scenario: Admin loads the Settings index
- **WHEN** an admin requests `GET /admin/settings`
- **THEN** the response renders a page listing at least an "Email" entry
  linking to `/admin/settings/email`
- **AND** other groups (General, OAuth, AI) MAY appear as placeholders or
  be omitted; the Email entry MUST be present

#### Scenario: Non-admin loads the Settings index
- **WHEN** a non-admin user requests `GET /admin/settings`
- **THEN** the system responds with HTTP 403 or redirects to login
  consistent with other `/admin/**` URLs

### Requirement: Seed default values for new email setting keys

The system SHALL include the rows `smtp.encryption`, `smtp.from_name`, and
`smtp.reply_to` in `system_settings` so the Email Settings form binds to
existing rows on first load.

#### Scenario: Fresh install applies all migrations
- **WHEN** a fresh database has all Flyway migrations applied through V9
- **THEN** `system_settings` contains a row with
  `setting_key = 'smtp.encryption'`, `setting_group = 'SMTP'`,
  `setting_value = 'tls'`
- **AND** a row with `setting_key = 'smtp.from_name'`,
  `setting_group = 'SMTP'`, `setting_value = 'ULP'`
- **AND** a row with `setting_key = 'smtp.reply_to'`,
  `setting_group = 'SMTP'`, `setting_value = ''`

#### Scenario: Existing install applies V9 migration
- **WHEN** an existing database that already has V1 SMTP rows applies V9
- **THEN** the migration inserts the three new rows without modifying any
  existing rows
- **AND** the migration does not add any new columns to `system_settings`
- **AND** re-applying V9 on a database where the three new rows already
  exist is a no-op (idempotent via `ON DUPLICATE KEY UPDATE setting_value = setting_value`)
