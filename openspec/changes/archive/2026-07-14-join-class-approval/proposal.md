## Why

Students who redeem an invite CODE or LINK currently become ACTIVE members immediately, but the class Members tab already shows a "Chờ duyệt" placeholder that never fills. Lecturers need control over who enters a class via self-join invites so uninvited or mistaken redemptions do not gain learning access without review.

## What Changes

- **BREAKING (behavior)**: CODE and LINK joins no longer create ACTIVE enrollments; they create PENDING join requests that require lecturer approval.
- Extend `enrollments.status` with `PENDING` and `REJECTED` (Flyway migration; keep ACTIVE / REMOVED / COMPLETED).
- Lecturer Members tab lists real pending requests with Approve / Reject actions (owner lecturer only).
- On approve: enrollment becomes ACTIVE, invite `use_count` increments, student is notified and gains class access.
- On reject: enrollment becomes REJECTED (row kept), student is notified; re-join via invite may open a new PENDING request.
- Student `/my/classes` surfaces pending requests ("đang chờ duyệt") without granting content access.
- Notifications: lecturer on new request; student on approve/reject; `CLASS_ENROLLED` only when ACTIVE (approve path).
- Import Excel and any future manual add remain immediate ACTIVE (lecturer-initiated).
- Existing access policies that admit only ACTIVE enrollments continue to block PENDING/REJECTED students from lessons, tests, flashcards, messaging, etc.

## Capabilities

### New Capabilities
- `join-class-approval`: Student invite join creates pending membership; class owner approves or rejects; pending students cannot access class content; capacity and invite use-count rules for the approval flow.

### Modified Capabilities
- `notifications`: Add join-request / join-approved / join-rejected notification types and emission points tied to the approval lifecycle (in-app; email optional/not required for these types).

## Impact

- **Schema**: `enrollments.status` CHECK constraint + entity constants.
- **Services**: `JoinClassService`, `JoinTokenValidator` (capacity remains ACTIVE-only), new or extended approval service, `ClassMembersService`, `StudentClassesService`.
- **Controllers/UI**: student join endpoints flash messaging; lecturer members tab + approve/reject routes; student my-classes pending state.
- **Notifications**: `NotificationType` constants + create calls.
- **Tests**: unit/integration for join state machine, approval, access denial while PENDING.
- **Out of scope**: per-class "require approval" toggle; enabling manual "Thêm học sinh"; Import requiring approval; non-owner approvers.
