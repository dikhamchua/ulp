# Mail Job Queue Rules (ULP)

**IMPORTANT:** Apply these rules whenever adding, changing, or reviewing code that sends email or fans out notifications to many users.

Package: `com.ulp.features.mail.job`

---

## When this applies

Trigger on any of:
- Calling `MailService.send` / `sendWithDetail`
- Creating notifications that might email (`NotificationService.create`, `EMAIL_TYPES`)
- Fan-out after publish / invite / bulk actions
- Anything that could mail N recipients from one HTTP request

---

## Hard rules

1. **Never send SMTP in a loop on the request thread.**
   - ❌ `for (student : students) { mailService.send(...); }`
   - ✅ Enqueue `MailJob`s (or use `NotificationService.create` for whitelisted types)

2. **Fan-out / bulk / non-interactive mail → queue only.**
   - Use `MailJobEnqueueHelper.enqueueAfterCommit(job)`
   - Prefer after-commit so the worker never races an open transaction

3. **Interactive single mail the user is waiting on → sync is OK.**
   - Password reset, admin "Gửi thử" SMTP
   - Use `MailService.send` / `sendWithDetail` directly

4. **Do not start your own mail thread or call `queue.take()`.**
   - Only `MailJobWorker` drains the queue

5. **Do not assume the in-memory queue survives restart.**
   - Best-effort only. Critical must-deliver interactive mail stays sync (or a future durable queue impl)

6. **Queue full / send failure must not fail the business action.**
   - `enqueue` returning `false` → log and continue
   - Publish / save still succeeds

7. **Lesson publish does not email.**
   - `LESSON_PUBLISHED` is in-app only (not in `EMAIL_TYPES`)
   - Re-enable later only by adding the type to `EMAIL_TYPES` — do not add a sync send path

---

## Building blocks (do not reinvent)

| Type | Role |
|---|---|
| `MailJob` | Work unit: to, subject, body, optional notificationId, source |
| `MailJobQueue` | Interface (`enqueue` non-blocking, `take` blocking) |
| `InMemoryMailJobQueue` | Default bounded impl (cap 2000; drop when full) |
| `MailJobEnqueueHelper` | After-commit enqueue helper |
| `MailJobWorker` | Single daemon thread `ulp-mail-job-worker` |

Flow:

```
request thread
  → MailJobEnqueueHelper.enqueueAfterCommit(job)
  → MailJobQueue
  → MailJobWorker
  → MailService.send → SMTP
  → optional: mark notifications.is_email_sent
```

---

## How to implement (copy patterns)

### Enqueue from a `@Transactional` service

```java
mailJobEnqueueHelper.enqueueAfterCommit(
        MailJob.forNotification(
                recipientEmail,
                "[ULP] " + title,
                body,
                notification.getId(),  // or null
                "MY_FEATURE_SOURCE"));
```

Standalone (no notification row):

```java
mailJobEnqueueHelper.enqueueAfterCommit(
        MailJob.of(email, subject, body, "INVITE_BULK"));
```

### Through in-app notifications

```java
notificationService.create(
        userId, title, body,
        NotificationType.ASSIGNMENT_PUBLISHED, // must be in EMAIL_TYPES to mail
        NotificationType.REF_ASSIGNMENT,
        assignmentId);
```

To make a notification type also email:
1. Add the constant to `NotificationType.EMAIL_TYPES`
2. Keep using `NotificationService.create`
3. Do **not** call `MailService.send` in the fan-out loop

---

## Current email policy

| Type / action | In-app | Email |
|---|---|---|
| `LESSON_PUBLISHED` | yes | **no** |
| `ASSIGNMENT_PUBLISHED` | yes | yes (queued) |
| `ASSIGNMENT_GRADED` | yes | no |
| Join lifecycle / `CLASS_ENROLLED` | yes | no |
| Password reset | — | sync `MailService` |
| Admin SMTP test-send | — | sync `MailService.sendWithDetail` |

---

## Checklist before merging mail-related work

- [ ] No `MailService.send` inside a request-thread loop
- [ ] Fan-out uses `MailJobEnqueueHelper` or `NotificationService.create`
- [ ] `source` label on `MailJob` is stable and greppable
- [ ] Unit tests mock `MailJobEnqueueHelper` (not SMTP) for queued paths
- [ ] New notification types documented in the policy table above if they mail
- [ ] Lesson publish path still does not send email unless product explicitly asks

---

## Do not

- ❌ Add Redis/Kafka/another broker just for mail without an explicit product decision
- ❌ Block HTTP waiting for the worker
- ❌ Enqueue before commit without the helper when the job references rows from the same TX
- ❌ Duplicate worker loops in feature packages
