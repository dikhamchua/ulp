# Spec — comment-moderation

## ADDED Requirements

### Requirement: Moderator may hide a lesson comment
A moderator SHALL be able to hide a lesson comment, transitioning its
`moderation_status` from `APPROVED` to `REJECTED`. A moderator is the owning
lecturer of the comment's class, or any user with role `ADMIN` or `HEAD`.

#### Scenario: Owning lecturer hides a comment
- **WHEN** the owning lecturer sends `POST /api/lessons/{lessonId}/comments/{commentId}/hide`
- **THEN** the comment's `moderation_status` becomes `REJECTED`
- **AND** a `comment_moderation` row is written with `moderated_by` = the lecturer and `action` = `REJECTED`
- **AND** the response is a success `AjaxResult`

#### Scenario: ADMIN not enrolled hides a comment
- **GIVEN** an `ADMIN` who is not enrolled in the comment's class
- **WHEN** they hide the comment
- **THEN** the action succeeds and the comment becomes `REJECTED`

#### Scenario: Student is forbidden from hiding
- **WHEN** an enrolled student calls the hide endpoint
- **THEN** the response is `403` and the comment status is unchanged

#### Scenario: Hiding is idempotent
- **GIVEN** a comment already `REJECTED`
- **WHEN** a moderator hides it again
- **THEN** the response is a success and no duplicate state change is required

### Requirement: Moderator may unhide a lesson comment
A moderator SHALL be able to unhide a previously hidden comment, restoring its
`moderation_status` from `REJECTED` to `APPROVED`.

#### Scenario: Moderator unhides a hidden comment
- **WHEN** a moderator sends `POST /api/lessons/{lessonId}/comments/{commentId}/unhide`
- **THEN** the comment's `moderation_status` becomes `APPROVED`
- **AND** a `comment_moderation` row is written with `action` = `APPROVED`
- **AND** the comment is visible again to students

#### Scenario: Unhiding is idempotent
- **GIVEN** a comment already `APPROVED`
- **WHEN** a moderator unhides it
- **THEN** the response is a success and the status remains `APPROVED`

### Requirement: Hidden comments are invisible to students
A hidden (`REJECTED`) comment SHALL NOT appear in the thread for students or the
public read path. A hidden root comment SHALL drop its entire thread from the
student view, identical to a deleted root.

#### Scenario: Student does not see a hidden comment
- **GIVEN** a comment that has been hidden
- **WHEN** a student loads the lesson thread
- **THEN** the hidden comment is absent from the returned comments

#### Scenario: Hidden root removes its thread for students
- **GIVEN** a hidden root comment that has non-deleted replies
- **WHEN** a student loads the thread
- **THEN** neither the root nor its replies appear

### Requirement: Moderators see hidden comments flagged for unhide
When the caller is a moderator, the thread listing SHALL include hidden comments,
each flagged so the client can render them dimmed with an unhide control. Every
comment a moderator can act on SHALL be flagged as moderatable.

#### Scenario: Moderator sees a hidden comment as hidden
- **GIVEN** a hidden comment on the lesson
- **WHEN** a moderator loads the thread
- **THEN** the comment is returned with `hidden` = true and `canModerate` = true

#### Scenario: Moderator sees visible comments as moderatable
- **WHEN** a moderator loads the thread
- **THEN** each visible comment is returned with `canModerate` = true and `hidden` = false

#### Scenario: Comment not found or not in lesson
- **WHEN** a moderator targets a comment id that does not belong to the lesson
- **THEN** the response is `404` with no existence leak
