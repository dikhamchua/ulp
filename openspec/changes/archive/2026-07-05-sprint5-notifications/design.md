## Context

ULP already ships direct messaging (`com.ulp.features.messaging`) with a header
badge driven by a `@ControllerAdvice`, an unread-count service method, and a
`/my/messages` SSR surface. The `notifications` table exists in `V1` but is
unused. This change reuses the messaging shape almost verbatim for a parallel
notifications feature, and reuses `MailService` (`DbConfiguredMailSender`) for
best-effort email. Constraints from `CLAUDE.md`: Flyway owns schema (Hibernate
`validate`), SSR Thymeleaf only, `UlpToast` for all user feedback, English code
comments / Vietnamese UI text, controller constants via `IConstant` static
import, Java files kept small.

## Goals / Non-Goals

**Goals:**
- Authenticated users can view their own notifications and see an unread badge.
- Opening a notification marks it read (owner-only) and follows a reference link.
- Enrollment and lesson-publish events create notifications.
- Important notifications (`LESSON_PUBLISHED`) trigger a best-effort email.
- No schema change — only a seed migration (`V22`).

**Non-Goals:**
- Admin UI to compose/send notifications manually.
- Async/scheduled email delivery or retry queue.
- Per-user email preference (opt-in/opt-out).
- Realtime push (STOMP) — messaging has it, notifications poll instead.

## Decisions

### D1: Mirror the messaging feature shape
Build `com.ulp.features.notifications` with the same layering messaging uses
(entity, repository, service, controller, DTOs, `@ControllerAdvice`). Rationale:
the team already understands this shape; the badge/advice/unread-count pattern is
proven. Alternative (a generic "inbox" abstraction over both messages and
notifications) was rejected as premature — YAGNI, and the two have different
lifecycles (conversations vs one-shot events).

### D2: `notifications` table used as-is, entity in `validate` mode
Map the entity to the existing columns exactly (`id, user_id, title, content,
type, reference_type, reference_id, is_read, read_at, is_email_sent,
created_at`). No `@Data` on the entity (per project rule). `V22` only seeds demo
rows so the page isn't empty in a fresh DB. Rationale: Flyway owns schema and
Hibernate is `validate` — any column mismatch fails startup, which is the desired
guard.

### D3: `NotificationType` as string constants + email whitelist
Define types as constants (`LESSON_PUBLISHED`, `CLASS_ENROLLED`, `SYSTEM`) stored
in the `type` VARCHAR column. Email is sent only for a whitelist that currently
contains `LESSON_PUBLISHED`. Rationale: matches the existing status-as-VARCHAR
convention; a whitelist keeps the "important = email" rule in one place and
avoids emailing on every enrollment (spam).

### D4: Email is best-effort, synchronous, inside `create()`
`NotificationService.create(...)` persists the row first, then — if the type is
whitelisted — loads the recipient's email and calls `mailService.send(...)`,
setting `is_email_sent = true` only on success. Rationale: KISS per `CLAUDE.md`;
`MailService.send` already returns a boolean and never throws, so the in-app
notification is safe even when SMTP is unconfigured or delivery fails.

### D5: Owner-scoped reads and mark-read (no-leak)
All queries filter by `user_id = caller`. `markRead(userId, notifId)` only
updates a row that belongs to the caller; a foreign/absent id is a silent no-op
(treated as not found), never a 403 that leaks existence. Rationale: same no-leak
stance as `MessagingService`.

### D6: Hook points — after successful enrollment, on lesson publish
- `JoinClassService`: emit `CLASS_ENROLLED` only on the `Success` (new
  enrollment) branch, not on `AlreadyJoined`, so duplicates don't re-notify.
- `LessonsPublishService`: on publish, look up enrolled students via
  `EnrollmentRepository` and emit one `LESSON_PUBLISHED` per student.
Both inject `NotificationService` as a constructor dependency (additive; no
signature change to existing public methods). Impact analysis:
`LessonsPublishService` upstream = 1 importer (its controller), risk LOW.

### D7: Reference-driven redirect on open
A notification may carry `reference_type` + `reference_id` (e.g. a class or
lesson). Opening one marks it read, then redirects to the referenced resource's
existing route if a reference is present; otherwise it stays on the list.
Rationale: turns a notification into an actionable link without new routing.

## Risks / Trade-offs

- **[Fan-out on publish in a large class]** publishing a lesson to a class with
  many students creates N rows + up to N emails synchronously → slower publish.
  Mitigation: acceptable for capstone scale; email is best-effort and the
  whitelist is narrow. A future async queue is noted as a Non-Goal.
- **[No email preference]** users can't opt out of `LESSON_PUBLISHED` email.
  Mitigation: whitelist is intentionally minimal; adding a preference is a
  separate, larger change.
- **[Seed data collides with real users]** `V22` seeds demo rows referencing
  seeded users. Mitigation: reference user ids that exist from earlier seed
  migrations (test users), guard with existence-safe inserts.
- **[Entity/column drift]** if the entity doesn't match V1 columns exactly,
  `validate` fails at boot. Mitigation: map columns explicitly against the V1
  definition; integration test boots the context and catches drift.

## Migration Plan

1. Add `V22__seed_demo_notifications.sql` — seed a few rows for existing seeded
   users (no DDL). Flyway applies it on next boot.
2. Deploy code; `@ControllerAdvice` starts contributing the badge count.
3. Rollback: notifications are additive and side-effect-only. To disable, revert
   the code; the seed rows are harmless demo data (can be deleted by a follow-up
   migration if needed). No destructive rollback required.

## Open Questions

None — all decisions locked during exploration (source, email strategy, and
audience were confirmed by the user).
