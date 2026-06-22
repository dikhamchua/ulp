# 0008 SMTP Password Plain-Text Storage

Date: 2026-06-22

## Status

Accepted (MVP technical debt — revisit before production launch)

## Context

The Email Settings change (`openspec/changes/email-settings-mvp/`) stores SMTP
configuration in the `system_settings` key-value table so admins can edit
transport credentials at runtime via `/admin/settings/email` without
redeploying. The `smtp.password` row holds the credential used by
`DbConfiguredMailSender` to authenticate with the SMTP server.

The team chose to store `smtp.password` as plain text in
`system_settings.setting_value`, with masking enforced only at the HTTP
response layer (`EmailSettingsService.SECRET_KEYS = {"smtp.password"}` →
substitute `********` before form bind). This decision was made consciously
under MVP scope constraints; the agent flagged the security trade-off and the
user accepted the risk.

## Decision

`smtp.password` is stored in plain text inside `system_settings.setting_value`.

- No encryption-at-rest (no AES, no `TextEncryptor`).
- No external secret manager (no Vault, no AWS Secrets Manager).
- The schema column `is_encrypted` exists but is left `0` for all SMTP rows
  and is **not** consulted by the masking logic.
- Masking on HTTP GET responses is done at the service layer via a hardcoded
  `Set<String> SECRET_KEYS` constant, so the wire format never exposes the
  real password value to the browser.

## Alternatives Considered

1. **AES-256 encryption with an app-scoped key** (Spring `TextEncryptor`).
   ~30 minutes of extra implementation: helper class, key sourced from
   environment variable, migration of stored value on save. Rejected for MVP
   to keep scope tight; user explicitly declined despite the agent's
   recommendation.
2. **Add an `is_secret` column to `system_settings`** and let the entity
   carry that flag for service-layer masking. Rejected: introduces a schema
   change for what is currently a single row; hardcoded `SECRET_KEYS` set
   gives the same masking result without a migration.
3. **External secret manager** (HashiCorp Vault, AWS Secrets Manager,
   Spring Cloud Vault). Over-engineered for a capstone project with a
   single-server deployment target.

## Consequences

Positive:

- Zero added complexity in the data layer; admins can edit the password
  through the admin UI exactly like any other setting.
- No new operational dependency (no key rotation, no vault sidecar).
- Schema stays the same; rollback only requires reverting code.

Tradeoffs:

- **Plain-text password on disk.** Any actor with read access to the DB
  (SQL injection, leaked backup, casual dev access to prod DB, intern
  pushing a `.sql` dump to Git) can recover the live SMTP credential.
- **Blast radius if leaked:** spammer can send phishing under the project's
  domain → Gmail/Outlook reputation blacklist → password reset emails stop
  being delivered. If using Gmail App Password, the Google account itself
  gets locked. If using a paid SMTP provider (SendGrid, SES), the customer
  may be billed for the abuse.
- The `is_encrypted` column is now a misleading no-op. Future maintainers
  may read the schema and assume secrets are encrypted; the column is
  superseded by service-layer logic.
- Violates the "Audit/security" hard gate in `docs/FEATURE_INTAKE.md`
  (which would normally push this work into the high-risk lane). The lane
  classification was kept at "normal" because the user explicitly narrowed
  scope on the trade-off.

## Follow-Up

- **Revisit trigger:** Before any production launch, OR when the project
  crosses ~100 active users, OR when the SMTP provider is upgraded from
  Gmail App Password / Mailtrap sandbox to a paid transactional provider
  (Resend, SendGrid, SES) — whichever comes first.
- **Migration path** when revisiting:
  1. Add `TextEncryptor` bean keyed off an env var (`ULP_APP_KEY`).
  2. Wrap `EmailSettingsService.save()` to encrypt `smtp.password` before
     write; wrap `DbConfiguredMailSender.send()` to decrypt before use.
  3. One-shot migration: read existing plain values, encrypt, write back,
     flip `is_encrypted=1` for `smtp.password` row.
  4. Update this decision record's status to `Superseded`.
- **In the meantime:** ensure the production DB has tight ACLs, prod
  backups are encrypted at rest, and `application-local.properties` no
  longer holds any SMTP credential (already enforced — see
  `application-local.properties.example` SMTP block marked LEGACY).
