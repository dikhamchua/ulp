## 1. Schema and entity

- [x] 1.1 Add Flyway `V24__enrollment_pending_rejected.sql` expanding `enrollments.status` CHECK to include `PENDING` and `REJECTED`
- [x] 1.2 Update `Enrollment` entity with `STATUS_PENDING`, `STATUS_REJECTED` and helpers to mark pending / reject / activate from pending ← (verify: CHECK and Java constants match; no ACTIVE default on invite-pending factory path)

## 2. Join request path (CODE/LINK → PENDING)

- [x] 2.1 Change `JoinClassService.join` to create PENDING for CODE/LINK; stop incrementing `use_count` on request; stop emitting `CLASS_ENROLLED` on request
- [x] 2.2 Handle existing statuses: ACTIVE → AlreadyJoined; PENDING → PendingRequested no-op; REJECTED/REMOVED → PENDING again; COMPLETED → reject
- [x] 2.3 Extend `JoinResult` with `PendingRequested`; update `StudentClassesController` and `InviteLinkController` flash messages for pending / already-pending
- [x] 2.4 Notify class owner with `JOIN_REQUEST` on new/re-opened PENDING only (not on idempotent PENDING) ← (verify: unit tests cover all status branches + use_count unchanged on request)

## 3. Approval / rejection path

- [x] 3.1 Implement owner-only approve: PENDING→ACTIVE, capacity re-check, `use_count++` under lock when invite present, emit `JOIN_APPROVED` + `CLASS_ENROLLED`
- [x] 3.2 Implement owner-only reject: PENDING→REJECTED, no use_count change, emit `JOIN_REJECTED`
- [x] 3.3 Wire lecturer controller endpoints (POST approve/reject) with ownership checks and flash/redirect to Members tab ← (verify: non-owner denied; full class blocks approve; disabled invite still approvable)

## 4. Members and student UI

- [x] 4.1 Load PENDING list + count in members view model; replace hard-coded "Chờ duyệt • 0" with real rows and Approve/Reject actions in `detail-members.html`
- [x] 4.2 Surface PENDING classes on student `/my/classes` as "đang chờ duyệt" without treating them as ACTIVE content access
- [x] 4.3 Add any new routes/messages to `IConstant` with static import in controllers ← (verify: UI empty/success states; CSRF on approve/reject forms)

## 5. Notifications and access

- [x] 5.1 Add `JOIN_REQUEST`, `JOIN_APPROVED`, `JOIN_REJECTED` to `NotificationType` (not in `EMAIL_TYPES`)
- [x] 5.2 Confirm learning surfaces still require ACTIVE only (no regression that admits PENDING) ← (verify: PENDING cannot open lessons/tests; CLASS_ENROLLED not fired on request)

## 6. Tests

- [x] 6.1 Update `JoinClassServiceTest` / `ClassInviteJoinIntegrationTest` for PENDING outcomes and edge cases
- [x] 6.2 Add tests for approve/reject service + controller (owner, non-owner, capacity full, reject then re-request)
- [x] 6.3 Run targeted Maven tests and fix failures in scope ← (verify: join + approval integration scenarios pass)
