## Why

ULP has direct messaging (Epic #13, ULP-8.3/8.4) but no system-generated
notifications. Students have no in-app surface to learn about class events —
enrolling in a class, or a lecturer publishing a new lesson. Sprint 5 stories
#63 (view system notifications) and #64 (receive email on important updates)
close this gap. The `notifications` table already exists in the schema (V1) but
is unused; this change wires it into a working feature.

## What Changes

- Introduce a new `com.ulp.features.notifications` package (entity, repository,
  service, controller, DTOs, controller advice) modelled on the existing
  `messaging` feature.
- Add a bell icon with an unread-count badge to the shared header
  (`fragments/app-header`), next to the existing chat badge.
- Add a `/my/notifications` page listing the signed-in user's notifications
  (newest first, unread emphasized). Opening a notification marks it read and,
  when it carries a reference, redirects to the linked resource.
- Generate notifications from two existing events:
  - Successful class enrollment → `CLASS_ENROLLED` notification for the student.
  - Lesson publish → `LESSON_PUBLISHED` notification for every enrolled student.
- Send a best-effort email (via the existing `MailService`) synchronously when
  a notification of an email-whitelisted type (`LESSON_PUBLISHED`) is created,
  then set `is_email_sent = 1`. SMTP failure never breaks the in-app path.
- Add Flyway migration `V22` that only **seeds** demo notification rows (the
  table already exists — no schema change).

## Capabilities

### New Capabilities
- `notifications`: In-app system notifications for authenticated users —
  storage/retrieval, unread-count badge, mark-as-read, event-driven creation
  from enrollment and lesson-publish, and synchronous best-effort email
  delivery for important types.

### Modified Capabilities
<!-- No existing OpenSpec capability specs change their requirements. The
     enrollment and lesson-publish flows gain a notification side-effect, but
     their own behavioral contracts are unchanged. -->

## Impact

- **New code**: `com.ulp.features.notifications.*` (entity, repository,
  service, controller, DTOs, `NotificationHeaderAdvice`).
- **Modified code**:
  - `JoinClassService` — inject `NotificationService`, emit `CLASS_ENROLLED`
    after a successful (non-duplicate) enrollment.
  - `LessonsPublishService` — inject `NotificationService`, emit
    `LESSON_PUBLISHED` to enrolled students on publish.
  - `common.IConstant` — add notification route/view/attribute constants.
  - `fragments/app-header.html` — add the bell + badge.
- **Reused**: `MailService` / `DbConfiguredMailSender` (best-effort email),
  `EnrollmentRepository` (enrolled-student lookup), iziToast/`UlpToast`.
- **New assets**: `templates/notifications/index.html`,
  `static/css/notifications.css`, `static/js/notifications.js`.
- **Migration**: `V22__seed_demo_notifications.sql` (seed only).
- **DB**: existing `notifications` table (read/write); no schema change,
  Hibernate stays in `validate` mode.
- **GitHub issues**: #63, #64, #226–#230.
