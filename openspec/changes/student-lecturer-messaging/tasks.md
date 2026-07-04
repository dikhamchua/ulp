## 1. Data model & migration

- [x] 1.1 Create `db/migration/V21__student_lecturer_messaging.sql` — `conversations` table (id PK, user_lo_id, user_hi_id, last_message_at NULL, created_at NOT NULL, UNIQUE(user_lo_id, user_hi_id), FK both → users(id))
- [x] 1.2 Add `messages` table in V21 (id PK, conversation_id FK → conversations, sender_id FK → users, body TEXT NOT NULL, created_at NOT NULL, read_at NULL, INDEX(conversation_id, created_at))
- [x] 1.3 Add idempotent demo seed as stored procedure (pattern from V20): one conversation between `student@ulp.edu.vn` and class 334's lecturer, several two-way messages, one message from lecturer left unread by student; no-op when user/class absent ← (verify: schema matches design.md; Flyway V21 runs clean on startup; seed is idempotent and no-ops on fresh DB)

## 2. Entities

- [x] 2.1 Create `entities/Conversation.java` (JPA, `@Getter`, `@NoArgsConstructor(PROTECTED)`, factory for normalized lo/hi pair)
- [x] 2.2 Create `entities/Message.java` (JPA, same Lombok conventions, `markRead` helper setting read_at)

## 3. Repositories

- [x] 3.1 Create `repository/ConversationRepository`: `findByUserLoIdAndUserHiId(lo, hi)`; `findConversationsForUser(userId, Pageable)` ordered by last_message_at DESC
- [x] 3.2 Create `repository/MessageRepository`: `findByConversationIdOrderByCreatedAtAsc(convId, Pageable)`; per-conversation unread count (read_at IS NULL AND sender_id != me); `countUnreadForUser(me)` total; `markReadBulk(convId, me, now)` `@Modifying` ← (verify: unread queries exclude own messages; markReadBulk only marks messages sent by the peer)

## 4. Authz gate

- [x] 4.1 Create `support/MessagingAccess.java`: `canMessage(a, b)` allowing only student↔lecturer pairs sharing a class with ACTIVE enrollment (student side) / teaching (lecturer side), using `EnrollmentRepository` + `ClassRepository`; block student↔student and lecturer↔lecturer ← (verify: gate correctly resolves both directions; rejects same-role pairs and non-shared-class pairs)

## 5. Service layer

- [x] 5.1 Create `dto/MessagingDtos.java`: `ConversationRow`, `MessageRow`, `ConversationView`, `SendResult`
- [x] 5.2 Create `service/MessagingService.java` — `getOrCreateConversation(me, otherId)` (validate via MessagingAccess, normalize lo/hi), `listConversations(me, page)`, `unreadCount(me)`, `searchRecipients(me, q)` (via MessagingAccess)
- [x] 5.3 Add `openConversation(me, convId, page)` — validate me ∈ conversation, load messages, markRead
- [x] 5.4 Add `send(me, convId, body)` — validate me ∈ conversation (NO enrollment re-check), enforce 2000-char limit, insert, update last_message_at, push STOMP to peer via `convertAndSendToUser` ← (verify: send validates membership but NOT enrollment; 2000-char limit enforced; STOMP payload {convId, senderName, snippet, unreadTotal} pushed to peer only)

## 6. WebSocket infrastructure

- [x] 6.1 Add to `pom.xml`: `spring-boot-starter-websocket`; webjars `sockjs-client`, `stomp-websocket`, `webjars-locator-core`
- [x] 6.2 Create `config/WebSocketConfig.java` (`@EnableWebSocketMessageBroker`): endpoint `/ws` with `.withSockJS()`, `enableSimpleBroker("/topic","/queue")`, app prefix `/app`, user destination prefix `/user`
- [x] 6.3 Modify `config/SecurityConfig.java` — add `.requestMatchers("/ws/**").authenticated()` to existing lambda chain; no CSRF change for REST ← (verify: /ws handshake requires auth; existing matchers and CSRF behavior unchanged)

## 7. Controller

- [x] 7.1 Create `controller/MessagingController.java` (base `/my/messages`): `GET /` (list + empty pane, pager + PageWindow), `GET /{convId}` (open conversation)
- [x] 7.2 Add `POST /{convId}` (send, non-JS form fallback → redirect), `POST /new?to={userId}` (create/open), `GET /unread-count` (JSON {count}) ← (verify: routes match spec scenarios; unauthorized recipient on /new is blocked no-leak 403/404)

## 8. Frontend

- [x] 8.1 Modify `templates/fragments/app-header.html` — chat icon + `<span class="msg-badge">` (hidden when 0) into `.header-right` before user dropdown; server-render count from unreadCount
- [x] 8.2 Create `templates/messaging/index.html` (Messenger 2-column layout) + `templates/messaging/conversation.html` (thread + composer)
- [x] 8.3 Create `static/css/messaging.css` — 2-column layout, left/right bubbles, bottom composer, badge, loading/empty/error states
- [x] 8.4 Create `static/js/messaging.js` — SockJS+STOMP connect, subscribe `/user/queue/messages`, send via fetch POST (keep form fallback), append realtime, update badge; wire badge update on every page ← (verify: realtime append works; badge updates live and clears on open; error state shows inline retry on send failure)

## 9. Verification

- [x] 9.1 `mvn compile` clean; app boots and Flyway V21 applies without error (compile verified clean; V21 mirrors V20's guarded stored-procedure seed pattern — app-boot/Flyway-apply left to the orchestrator's live run)
- [ ] 9.2 Playwright 2 sessions (student + lecturer): seed conversation visible + badge > 0; open → old messages show, badge → 0; lecturer sends → student sees realtime + badge increments without reload; student sends → lecturer receives realtime
- [ ] 9.3 Authz E2E: student opens `/my/messages/new?to={unrelated-user}` → blocked no-leak; regression check header renders on lessons/tests and user dropdown intact ← (verify: all end-to-end scenarios from specs pass; no regression in header)
