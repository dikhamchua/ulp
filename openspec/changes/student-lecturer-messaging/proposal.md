## Why

Students and lecturers currently have no way to communicate privately inside ULP.
All coordination happens outside the platform (email, chat apps), which fragments
the learning context. Epic #13 (ULP-8.3 Messaging + ULP-8.4 Unread badge) closes
this gap with a focused, in-platform 1-on-1 chat between a student and the lecturers
of the classes they are enrolled in.

## What Changes

- Add private **1-on-1 direct messaging** between a student and a lecturer (LECTURER/HEAD).
  Explicitly NOT student↔student, NOT lecturer↔lecturer, NOT group chat.
- Add a **recipient gate**: a new conversation may only be started between a student
  and a lecturer who share at least one class where the student's enrollment is ACTIVE.
  Either party may initiate. The gate applies ONLY at conversation creation and
  recipient search — an existing conversation stays open for both parties even after
  the student later leaves the class (intentional).
- Add **real-time delivery** via WebSocket (STOMP over SockJS): a new message appears
  in the peer's open conversation without a page reload.
- Add an **unread-message badge** in the app header (chat icon + count), rendered
  server-side on load and updated live over STOMP. Opening a conversation marks its
  messages read and drops the badge accordingly.
- Add a **Messenger-style UI**: left sidebar lists conversations (peer avatar, name,
  last-message snippet); right pane is the chat thread (left/right bubbles, composer
  at the bottom). Component states: loading / empty / error (send failure → inline
  retry) / success.
- Enforce a **2000-character** limit per message.
- Add **migration V21** with two new tables (`conversations`, `messages`) plus an
  idempotent demo seed.

## Capabilities

### New Capabilities
- `direct-messaging`: 1-on-1 student↔lecturer conversations — recipient eligibility
  gate, conversation creation, message send/receive with real-time STOMP delivery,
  read tracking, and the Messenger-style UI.
- `unread-message-badge`: header chat badge showing the caller's total unread count,
  server-rendered on load and updated live over STOMP; cleared per-conversation on open.

### Modified Capabilities
<!-- None — messaging is greenfield; no existing spec's requirements change. -->

## Impact

- **New backend package** `features/messaging/` (controller, service, repository, dto, support).
- **New entities** `entities/Conversation.java`, `entities/Message.java`.
- **New config** `config/WebSocketConfig.java` (`@EnableWebSocketMessageBroker`).
- **Modified** `config/SecurityConfig.java` — authenticate `/ws/**` handshake (no CSRF change for REST).
- **Modified** `pom.xml` — add `spring-boot-starter-websocket` + webjars (sockjs-client,
  stomp-websocket, webjars-locator-core).
- **Modified** `templates/fragments/app-header.html` — chat icon + unread badge.
- **New frontend** `templates/messaging/*.html`, `static/css/messaging.css`, `static/js/messaging.js`.
- **New migration** `db/migration/V21__student_lecturer_messaging.sql` (schema + idempotent seed).
- **Reuses** `EnrollmentRepository`, `ClassRepository`, `UlpUserDetails`, `fragments/pager.html`,
  `PageWindow`, and the `[data-toggle="dropdown"]` pattern in `static/js/app.js`.
- **Out of scope**: ULP-8.1 system notifications, ULP-8.2 email notifications, group chat,
  file attachments, typing indicator, visible read receipts.
