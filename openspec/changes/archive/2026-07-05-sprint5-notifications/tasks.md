## 1. Domain layer (entity + type + repository)

- [x] 1.1 Create `com.ulp.features.notifications.entity.Notification` mapped to the
      existing `notifications` table (id, user_id, title, content, type,
      reference_type, reference_id, is_read, read_at, is_email_sent, created_at).
      No `@Data`; explicit getters/setters or `@Getter/@Setter`.
- [x] 1.2 Create `NotificationType` constants (`LESSON_PUBLISHED`, `CLASS_ENROLLED`,
      `SYSTEM`) plus the email whitelist set (contains only `LESSON_PUBLISHED`).
- [x] 1.3 Create `NotificationRepository`: page findByUserId ordered by created_at
      desc; countByUserIdAndIsReadFalse; lookup by id+userId for owner-scoped read.
      ← (verify: entity columns match V1 schema exactly so Hibernate `validate` boots)

## 2. Service layer

- [x] 2.1 Create `NotificationDtos` (record row/view for list + unread badge).
- [x] 2.2 Create `NotificationService.create(userId, title, content, type,
      referenceType, referenceId)`: persist row; if type in email whitelist, load
      recipient email and call `mailService.send(...)`, set is_email_sent=true only
      on success (best-effort, never throws).
- [x] 2.3 Add `listForUser(userId, page)`, `unreadCount(userId)`,
      `markRead(userId, notifId)` (owner-scoped, silent no-op for foreign/absent id).
      ← (verify: mark-read no-leak — a foreign id does not modify any row)

## 3. Web layer (controller + advice + constants)

- [x] 3.1 Add notification constants to `com.ulp.common.IConstant`
      (BASE_MY_NOTIFICATIONS, VIEW_NOTIFICATIONS_INDEX, ATTR_NOTIFICATIONS,
      ATTR_NOTIF_UNREAD, flash keys). Follow static-import rule.
- [x] 3.2 Create `NotificationHeaderAdvice` `@ControllerAdvice` exposing
      `notifUnreadCount` for every view (0 when anonymous), mirroring
      `MessagingHeaderAdvice`.
- [x] 3.3 Create `NotificationController` `@RequestMapping("/my/notifications")`
      `@PreAuthorize("isAuthenticated()")`: GET index (list), GET open/POST read
      (mark read → redirect to reference or back to list, PRG), GET /unread-count
      (JSON fallback). Use flash → UlpToast for feedback.
      ← (verify: all spec scenarios for view/mark-read/redirect covered; anonymous blocked)

## 4. Event hooks

- [x] 4.1 Inject `NotificationService` into `JoinClassService`; on the `Success`
      (new enrollment) branch emit a `CLASS_ENROLLED` notification referencing the
      class. Do NOT emit on `AlreadyJoined`.
- [x] 4.2 Inject `NotificationService` into `LessonsPublishService`; on publish,
      look up enrolled students (EnrollmentRepository) and emit one
      `LESSON_PUBLISHED` notification per student referencing the lesson.
      ← (verify: publish fan-out notifies each enrolled student; enrollment duplicate does not re-notify)

## 5. Frontend (Thymeleaf + assets)

- [x] 5.1 Create `templates/notifications/index.html` (list, unread emphasis,
      empty state, `#flash-data` block). UI text in Vietnamese.
- [x] 5.2 Add the bell icon + `notifUnreadCount` badge to
      `fragments/app-header.html` next to the chat badge.
- [x] 5.3 Create `static/css/notifications.css` and `static/js/notifications.js`
      (mark-read interaction, poll unread-count, drain `#flash-data` → UlpToast).
      ← (verify: no inline alert/native alert; all feedback via UlpToast)

## 6. Migration (seed only)

- [x] 6.1 Create `V22__seed_demo_notifications.sql` seeding a few demo rows for
      existing seeded users (no DDL, existence-safe inserts).
      ← (verify: migration runs clean on a fresh DB; no schema change; app boots)

## 7. Tests

- [x] 7.1 `NotificationServiceTest` (unit): create persists; email sent only for
      whitelisted type; is_email_sent set on success only; mark-read no-leak.
- [x] 7.2 `Sprint5NotificationsIntegrationTest` (`@SpringBootTest` +
      `@AutoConfigureMockMvc` + `@WithMockUser`): list page, unread badge advice,
      open/mark-read, unread-count endpoint, anonymous redirect.
      ← (verify: `.\mvnw.cmd test` passes green end-to-end)
