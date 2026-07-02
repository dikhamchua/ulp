# learning-progress Specification

## Purpose
TBD - created by archiving change sprint3-progress-and-lesson-comments. Update Purpose after archive.
## Requirements
### Requirement: Opening a lesson records IN_PROGRESS
When an ACTIVE-enrolled student successfully opens a published lesson (all four authz gates pass: ACTIVE enrollment, live class, lesson's section belongs to the class, lesson PUBLISHED and not soft-deleted), the system SHALL upsert a `learning_progress` row for (user, lesson). If no row exists, it SHALL create one with status `IN_PROGRESS` and `started_at` set to now. If a row already exists (either status), the open SHALL NOT change its status or timestamps (idempotent). Recording MUST NOT run when any authz gate fails, and a recording failure MUST NOT break lesson rendering.

#### Scenario: First open creates IN_PROGRESS
- **WHEN** an enrolled student opens a published lesson they have never opened
- **THEN** a `learning_progress` row exists with status `IN_PROGRESS`, non-null `started_at`, null `completed_at`

#### Scenario: Re-open is idempotent
- **WHEN** the student opens the same lesson again (status IN_PROGRESS or COMPLETED)
- **THEN** the row's status and timestamps are unchanged and no duplicate row is created

#### Scenario: Failed authz records nothing
- **WHEN** a non-enrolled user or a request for a DRAFT lesson hits the lesson page
- **THEN** no `learning_progress` row is created

### Requirement: Student can toggle lesson completion
The system SHALL expose a POST endpoint at `/my/classes/{classId}/lessons/{lessonId}/progress/toggle` for authenticated users. It SHALL apply the same four authz gates as lesson viewing; any gate failure SHALL respond 404 without revealing existence. On success it SHALL toggle the row: if current status is not `COMPLETED`, set status `COMPLETED`, `completed_at` = now, `progress_percent` = 100 (creating the row directly as COMPLETED if absent, with `started_at` = now); if current status is `COMPLETED`, set status `IN_PROGRESS`, `completed_at` = null, `progress_percent` = 0. The endpoint SHALL redirect (PRG) back to `/my/classes/{classId}/lessons?section={sectionId}&lesson={lessonId}` with a Vietnamese flash toast message (`flashSuccess`).

#### Scenario: Mark complete
- **WHEN** the student POSTs the toggle for a lesson with status IN_PROGRESS
- **THEN** the row becomes COMPLETED with non-null `completed_at`, `progress_percent` 100, and the response redirects to the lesson URL with a success flash attribute

#### Scenario: Unmark complete
- **WHEN** the student POSTs the toggle for a lesson with status COMPLETED
- **THEN** the row becomes IN_PROGRESS with null `completed_at`, `progress_percent` 0, and the response redirects with a success flash attribute

#### Scenario: Toggle without prior open
- **WHEN** the student POSTs the toggle for a lesson with no progress row
- **THEN** a row is created directly with status COMPLETED

#### Scenario: Non-enrolled toggle denied
- **WHEN** a user without ACTIVE enrollment POSTs the toggle
- **THEN** the response is 404 and no row is written

### Requirement: Progress aggregates on the student lessons page
The student lessons page view model SHALL include, computed only over PUBLISHED and non-soft-deleted lessons: (a) per-section completed count and published count, (b) class-wide completed count, published count, and integer percentage (0 when the class has no published lessons; rounding half-up). Completed means a `learning_progress` row with status `COMPLETED` for the viewing student.

#### Scenario: Overall percentage
- **WHEN** a class has 4 published lessons across sections and the student completed 1
- **THEN** the view model reports 1/4 completed and 25%

#### Scenario: DRAFT lessons excluded from denominator
- **WHEN** a section holds 2 published and 3 draft lessons and the student completed both published ones
- **THEN** that section reports 2/2 and the drafts do not affect any count

#### Scenario: Empty class
- **WHEN** the class has no published lessons
- **THEN** the overall percentage is 0 and no division error occurs

### Requirement: Progress UI on the student lessons page
The page SHALL render: (a) an overall progress bar with "n/m bài giảng · p%" text inside the sidebar class card; (b) per-section "x/y" counts next to each entry in the chapter switcher; (c) a visible completed indicator (✓ badge) on each completed lesson card in the right rail; (d) on the inlined lesson detail, a toggle button labeled "Đánh dấu hoàn thành" when the lesson is not completed and "Đã hoàn thành ✓" (reverting on click) when completed. All feedback messages SHALL use the `UlpToast` flash pattern; no inline alert elements.

#### Scenario: Completed badge in rail
- **WHEN** the student views a section where they completed one lesson
- **THEN** that lesson's card shows the completed indicator and other cards do not

#### Scenario: Toggle button reflects state
- **WHEN** the inlined detail shows a lesson the student already completed
- **THEN** the button renders in its completed state and submitting it reverts the lesson to not-completed

