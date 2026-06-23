# 0009 DB-Backed OAuth Client Registration

Date: 2026-06-23

## Status

Accepted (MVP technical debt — revisit before production launch)

## Context

The default Spring Boot pattern for OAuth2 login binds provider credentials
through `spring.security.oauth2.client.registration.google.*` properties.
Those properties are read once at application startup and feed an
in-memory `InMemoryClientRegistrationRepository`. Changing any of them
requires a full restart.

For ULP we need an admin to be able to (a) paste a Google Client ID and
Secret into the admin panel and (b) see the "Sign in with Google" button
appear immediately on `/login` without redeploying. This mirrors how the
SMTP settings flow (see decision 0008) already works: SMTP credentials
live in the `system_settings` table and are read on every send.

CLAUDE.md §11 explicitly forbids removing `@ConditionalOnProperty` from
optional integrations (Google OAuth specifically). That rule was authored
when Google credentials lived in `application-local.properties`. With
DB-backed registration, properties no longer drive availability, so the
conditional has no signal to read.

## Decision

OAuth credentials (`oauth.google.client_id`, `oauth.google.client_secret`,
`oauth.google.scope`) are stored in the `system_settings` table and
loaded on every request by `DbClientRegistrationRepository`. The two
related Spring beans — `CustomOidcUserService` and the `oauthFailureHandler`
in `SecurityConfig` — drop `@ConditionalOnProperty` and become
unconditional.

Runtime gating happens at three layers instead:

1. `DbClientRegistrationRepository.findByRegistrationId("google")`
   returns `null` when either client id or secret is blank. Spring
   Security responds 404 to `/oauth2/authorization/google` in that case.
2. `OauthSettingsService.isGoogleEnabled()` returns `false` under the
   same condition. The login template uses this to hide the "Sign in
   with Google" button.
3. `SecurityConfig.filterChain` still wires `.oauth2Login(...)`
   unconditionally — there is nothing to start without a registration,
   so leaving the filter chain branch attached is harmless.

This pattern matches MailService precedent in decision 0008: the bean
always exists; behaviour degrades silently when configuration is absent.

## Alternatives Considered

1. **Keep properties-based registration; admin UI writes back to
   `application-local.properties` on save.** Rejected: writing properties
   files at runtime breaks immutable-deploy assumptions and requires app
   restart to take effect, defeating the goal.
2. **Hybrid — load from properties at startup, allow DB override at
   runtime.** Rejected: two sources of truth invite drift, and the
   "what's actually live right now" question becomes harder to answer.
3. **Keep `@ConditionalOnProperty` but point it at a dummy property
   that's always set.** Rejected: lying to the framework. Future
   maintainers reading the annotation would expect property-driven
   gating that no longer exists.

## Consequences

Positive:

- Admin can enable Google sign-in by pasting credentials into the
  admin UI; the button appears on the next page load with no restart.
- Single source of truth for credentials (DB), consistent with SMTP
  settings (decision 0008).
- Disabling Google sign-in is symmetric: clear the client id row,
  next request the button is gone.

Tradeoffs:

- **Plain-text Google client secret on disk.** Same risk profile as
  SMTP password in decision 0008 — anyone with DB read access can
  recover the credential. Blast radius is smaller (a leaked Google
  OAuth secret lets an attacker pose as our app to Google but does not
  itself expose user data; users must still consent), but the secret
  should still be treated as sensitive.
- **One DB query per `/login` page load** to evaluate
  `isGoogleEnabled()`, plus a second on every OAuth flow request to
  build the `ClientRegistration`. The lookups are by primary key
  (`findBySettingKey`) so cost is negligible for this project scale.
- **`@ConditionalOnProperty` is no longer the source of truth for
  whether Google sign-in is on**, contradicting CLAUDE.md §11. This
  decision record is the explicit deviation that the rule requires.
- Spring Boot's `OAuth2ClientAutoConfiguration` backs off as soon as
  our custom `ClientRegistrationRepository` bean is present, so we
  must also expose `OAuth2AuthorizedClientService` and
  `OAuth2AuthorizedClientRepository` beans manually
  (`InMemoryOAuth2AuthorizedClientService`,
  `AuthenticatedPrincipalOAuth2AuthorizedClientRepository`).
- Cached entries in `InMemoryOAuth2AuthorizedClientService` survive
  credential rotation. An admin changing the Google secret will not
  invalidate already-authorized clients; their access tokens (valid
  for one hour) continue to work until expiry. Acceptable at MVP
  scale; revisit if credential rotation becomes routine.

## Follow-Up

- **Revisit trigger:** Same threshold as decision 0008 — production
  launch, ~100 active users, or moving from a sandbox OAuth client to
  a verified Google Workspace app.
- **Migration path when revisiting:**
  1. Reuse the `TextEncryptor` bean introduced for SMTP password (see
     decision 0008 follow-up). Encrypt `oauth.google.client_secret`
     before write; decrypt in `DbClientRegistrationRepository`.
  2. Update `system_settings.is_encrypted` to `1` for the secret row
     after migration.
  3. Consider replacing `InMemoryOAuth2AuthorizedClientService` with
     a `JdbcOAuth2AuthorizedClientService` if multi-instance
     deployment is on the roadmap.
  4. Update this record to `Superseded`.
- **In the meantime:** the admin OAuth settings page is gated by
  `@PreAuthorize("hasRole('ADMIN')")`. The page renders the secret in
  plain text so the admin can audit what is stored; this is consistent
  with the SMTP password handling pattern and accepted under the same
  risk envelope.
- **Update CLAUDE.md §11:** the bullet that forbids removing
  `@ConditionalOnProperty` for Google OAuth must be edited to point at
  this decision record as the explicit exception, mirroring how the
  MailService bullet already references decision 0008.
