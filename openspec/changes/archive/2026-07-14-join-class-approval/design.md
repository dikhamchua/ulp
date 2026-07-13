## Context

ULP already supports invite tokens (`class_invite_codes` CODE/LINK), student join (`JoinClassService`, `/my/classes/join`, `/j/{token}`), and lecturer member list (`ClassMembersService` + `detail-members.html`). Join currently inserts or reactivates enrollments as **ACTIVE** immediately and increments invite `use_count` at join time.

The Members UI already has a static "Chờ duyệt" block (count hard-coded to 0). Schema `enrollments.status` is constrained to `ACTIVE|REMOVED|COMPLETED`. Learning surfaces (`ClassAccessPolicy`, tests, flashcards, messaging, progress) admit only **ACTIVE** enrollments.

Stakeholders: students self-joining; class owner lecturers approving; existing notification pipeline.

## Goals / Non-Goals

**Goals:**
- CODE/LINK self-join creates a **PENDING** enrollment requiring owner approval before class access.
- Owner can **Approve** (→ ACTIVE) or **Reject** (→ REJECTED) from Members tab.
- Capacity based on ACTIVE count is enforced at request and at approve.
- Invite `use_count` increments only on approve.
- Pending students see wait state on `/my/classes` and cannot open class content.
- Lecturer notified on request; student notified on approve/reject.
- Import / lecturer-initiated enrollments remain immediate ACTIVE.

**Non-Goals:**
- Per-class toggle to disable approval.
- Approvers other than class owner (HEAD/ADMIN may still view class via existing privileges but approve/reject is owner-only for this change).
- Enabling disabled "Thêm học sinh" manual add UI.
- Changing Import Excel to require approval.
- Email for join-request/approve/reject (in-app notifications only; types not added to `EMAIL_TYPES`).

## Decisions

### 1. Reuse `enrollments` row with new statuses (not a separate join_requests table)
- **Choice**: Add `PENDING` and `REJECTED` to `enrollments.status`.
- **Why**: Unique `(user_id, class_id)` already models one membership lifecycle; avoids dual tables and join-time races.
- **Alternative considered**: Separate `join_requests` table — cleaner "enrollment = admitted only", but more code paths and harder re-join reuse of the same unique key.

### 2. State machine for invite join
| Existing status | On CODE/LINK join |
|---|---|
| none | INSERT PENDING; notify lecturer |
| ACTIVE | AlreadyJoined (no-op) |
| PENDING | PendingRequested no-op (no use_count++) |
| REJECTED | set PENDING again; notify lecturer |
| REMOVED | set PENDING (not auto ACTIVE); notify lecturer |
| COMPLETED | reject with ALREADY_COMPLETED |

Approve: PENDING → ACTIVE, then `use_count++` on the invite row referenced by `invite_code_id` (if present), notify student (`JOIN_APPROVED` + `CLASS_ENROLLED` or single approved message — prefer JOIN_APPROVED for decision clarity and keep CLASS_ENROLLED for "now enrolled" consistency with existing copy, or emit CLASS_ENROLLED only if product wants one toast; **decision**: emit `JOIN_APPROVED` for the decision event; also emit `CLASS_ENROLLED` only if existing consumers depend on it — default to **JOIN_APPROVED for student decision + CLASS_ENROLLED for enrolled success** only when needed; **simplify**: student gets `JOIN_APPROVED` on approve and `JOIN_REJECTED` on reject; lecturer gets `JOIN_REQUEST` on new PENDING. Do **not** fire CLASS_ENROLLED on request. Fire **CLASS_ENROLLED on approve** so existing "enrolled" semantics stay true for ACTIVE membership).

Reject: PENDING → REJECTED; no use_count change; notify student.

### 3. Capacity
- Continue counting **ACTIVE only** via `countActiveByClassId`.
- Request path: if ACTIVE >= max_students → CLASS_FULL (same as today).
- Approve path: re-check capacity; if full → fail approve with clear flash/error, leave PENDING.

### 4. use_count timing
- **Do not** increment on PENDING create / re-request.
- **Do** increment under existing pessimistic lock pattern when approve succeeds (load invite by `invite_code_id`, lock if needed, enforce max_uses again, increment).
- If invite is disabled/expired at approve time: still allow approve (decision: old PENDING remains approvable); only block **new** requests via existing join validation. max_uses still enforced at approve before increment.

### 5. Authorization
- Approve/reject: class owner (`lecturerId == caller`) via existing class ownership helpers used by lecturer controllers. Non-owner LECTURER → 403; non-member student → 404/403 per existing class detail patterns.
- Listing pending: same access as Members tab (viewable class).

### 6. Access control for PENDING
- No change required to most gates if they already require `STATUS_ACTIVE`.
- Explicitly verify student class entry points and my-classes do not treat PENDING as enrolled for content links.
- `/my/classes` lists ACTIVE enrollments as today **and** surfaces PENDING rows as "đang chờ duyệt" (separate section or badge) without deep-linking into restricted tabs.

### 7. JoinResult expansion
- Add sealed outcome e.g. `PendingRequested(ClassEntity)` alongside `Success` / `AlreadyJoined`.
- Controllers map PendingRequested → flash info "Đã gửi yêu cầu tham gia lớp … — chờ giảng viên duyệt".
- **Success** remains for paths that become ACTIVE immediately (none for CODE/LINK after this change; Success may still apply if other channels call join — invite-only service stays invite-only).

### 8. Notifications
- New types: `JOIN_REQUEST`, `JOIN_APPROVED`, `JOIN_REJECTED` (VARCHAR fits existing length).
- Not in `EMAIL_TYPES`.
- Best-effort try/catch so notification failure never rolls back enrollment transaction (same pattern as current `emitEnrolledNotification`).

### 9. Migration
- `V24__enrollment_pending_rejected.sql`: drop/replace CHECK on `enrollments.status` to include PENDING, REJECTED (MySQL: typically drop check via alter table recreate constraint pattern used in project, or modify column with new CHECK).

## Risks / Trade-offs

- **[Risk] Existing tests/fixtures assume join → ACTIVE** → Update unit/integration tests; seed data stays ACTIVE (fine).
- **[Risk] Concurrent approves vs capacity** → Re-check ACTIVE count inside approve transaction; optional lock class/enrollments order consistent with join lock.
- **[Risk] use_count not incremented until approve means many PENDING can exist beyond max_uses** → Acceptable: max_uses gates successful admissions; optional future: count PENDING toward soft limit — out of scope.
- **[Risk] Dirty working tree has unrelated WIP (assignments, notifications)** → Implement only join-approval files; do not commit unrelated paths.
- **[Trade-off] Owner-only approve** vs any moderator → Matches "giảng viên chủ lớp"; HEAD/ADMIN can be added later.

## Migration Plan

1. Deploy Flyway V24 (additive statuses; no data rewrite).
2. Deploy app code that writes PENDING and approval endpoints.
3. Rollback: app rollback first (old code may not understand PENDING rows — avoid rolling back schema if PENDING rows exist; if needed, map PENDING→REMOVED manually). Prefer forward-only.

## Open Questions

None for implementation — all product decisions locked in conversation (★ set).
