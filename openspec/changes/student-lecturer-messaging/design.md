## Context

ULP has no in-platform private messaging. Students coordinate with lecturers over
external channels, fragmenting the learning context. Epic #13 (ULP-8.3 + ULP-8.4)
adds focused 1-on-1 student↔lecturer chat with real-time delivery and an unread badge.

The codebase is a Spring Boot MVC + Thymeleaf server-rendered app. Relevant existing
infrastructure the design reuses:
- `entities/Enrollment.java` — `(user_id, class_id, status)` with `STATUS_ACTIVE`.
- `entities/ClassEntity.java` — `lecturer_id` identifies the lecturer of a class.
- `features/classes/repository/EnrollmentRepository.java` — enrollment lookups.
- `features/classes/repository/ClassRepository.java` — class lookups by lecturer / id.
- `security/UlpUserDetails.java` — exposes `getId()`, `getRole()`, `getUsername()` (email).
- `security/Role.java` — `STUDENT, LECTURER, HEAD, ADMIN`.
- `templates/fragments/app-header.html` — shared header, `.header-right` holds the user dropdown.
- `templates/fragments/pager.html` + `common/PageWindow.java` — SSR pagination.
- `static/js/app.js` — `[data-toggle="dropdown"]` toggle + close-on-outside-click.
- Flyway migrations, highest is `V20` → next is `V21`. V20 shows the idempotent
  stored-procedure seed pattern.

## Goals / Non-Goals

**Goals:**
- 1-on-1 student↔lecturer conversations with a recipient eligibility gate at creation.
- Real-time message delivery + live unread badge via WebSocket STOMP.
- Messenger-style two-pane UI with full component states (loading/empty/error/success).
- Graceful non-JS fallback (form POST) so send works without the STOMP client.

**Non-Goals:**
- Student↔student or lecturer↔lecturer chat, group chat.
- File attachments, typing indicators, visible read receipts.
- System notifications (ULP-8.1) and email notifications (ULP-8.2) — later stories
  that will reuse the badge/dropdown infrastructure built here.

## Decisions

### D1 — Normalized conversation pair `(user_lo_id, user_hi_id)`
Store each conversation with the smaller user id in `user_lo_id` and the larger in
`user_hi_id`, with a `UNIQUE(user_lo_id, user_hi_id)`. This makes `getOrCreate`
idempotent regardless of who initiates, and prevents two conversation rows for the
same pair. Alternative (a directed `sender_id/recipient_id` pair) was rejected — it
allows duplicate rows for the same two people and complicates lookup.

### D2 — Recipient gate only at creation, not on send
`MessagingAccess` validates eligibility (student↔lecturer sharing an ACTIVE-enrollment
class) ONLY in `getOrCreateConversation` and `searchRecipients`. Once a conversation
exists, `send`/`openConversation` check only membership (caller is one of the two
participants), never re-check enrollment. This is intentional: a student who leaves a
class keeps their existing thread with the lecturer. Alternative (re-check on every
send) was rejected as contrary to the confirmed requirement and worse UX.

### D3 — Unread derived, not stored
"Unread" = `messages.read_at IS NULL AND sender_id != me`. No denormalized counter.
Total badge = count across all the caller's conversations. Opening a conversation runs
a bulk `markReadBulk(convId, me, now)`. Avoids counter drift; message volume here is low.

### D4 — WebSocket auth via HTTP session
STOMP handshake at `/ws` (SockJS) rides the existing form-login HTTP session, so the
Spring Security principal is already present — no separate WS token. Push to a specific
user with `SimpMessagingTemplate.convertAndSendToUser(email, "/queue/messages", payload)`.
`SecurityConfig` adds `.requestMatchers("/ws/**").authenticated()`. CSRF is unchanged:
the SockJS handshake is GET/WebSocket (outside CSRF), and REST POSTs keep CSRF via the
Thymeleaf token — we do NOT relax CSRF.

### D5 — STOMP client via Webjars
Load `sockjs-client` + `stomp-websocket` through webjars (+ `webjars-locator-core` for
version-agnostic paths) rather than a CDN. Keeps the app self-contained and offline-capable,
consistent with the Maven build.

### D6 — Send path: fetch POST + form fallback
The composer submits via `fetch` POST when JS is on (keeps the thread in place, appends
optimistically). The same endpoint also accepts a plain form POST and redirects back to
the conversation, so messaging degrades gracefully without JS. Real-time receive is a
STOMP subscription on `/user/queue/messages`.

## Risks / Trade-offs

- **In-memory simple broker** → single-instance only; unread/broadcast state is not shared
  across nodes. Mitigation: acceptable for current single-instance deployment; a full
  broker relay (RabbitMQ/ActiveMQ) is a later scaling step, out of scope.
- **Gate-at-creation lets a removed student keep chatting** → by design (D2). Mitigation:
  documented as intentional; membership check still prevents access to others' threads.
- **No message pagination cap on very long threads** → could grow large. Mitigation: the
  thread query is paged (`Pageable`); the sidebar list is paged via `PageWindow`.
- **STOMP push after DB commit** → if the push fails the message is still persisted and
  shows on next load/poll (`/my/messages/unread-count` fallback). Mitigation: badge is
  server-rendered on every page load, so live push is an enhancement, not the source of truth.

## Migration Plan

- Add `V21__student_lecturer_messaging.sql`: create `conversations` + `messages` with FKs
  and indexes, then an idempotent stored-procedure seed (one demo thread between
  `student@ulp.edu.vn` and class 334's lecturer, with one unread message). No-op when the
  users/class are absent, mirroring V20's guarded procedure.
- Forward-only (Flyway). Rollback = drop the two tables manually; no data depends on them yet.
