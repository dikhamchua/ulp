# mcq-exam-management Specification

## Purpose
TBD - created by archiving change add-mcq-online-exams. Update Purpose after archive.
## Requirements
### Requirement: Lecturer exam ownership boundary
The system SHALL restrict every lecturer exam action under `/lecturer/tests` to users
with role LECTURER, HEAD, or ADMIN, and further to exams the acting user owns — the exam
was created by them (`created_by`) or belongs to a class they lead (`class.lecturer_id`).
A non-owning lecturer action SHALL return 403; a request for a non-existent or soft-
deleted exam SHALL return 404 without leaking existence.

#### Scenario: Owner reaches their exam
- **WHEN** a lecturer opens the management screen of an exam in a class they lead
- **THEN** the screen renders for editing and monitoring

#### Scenario: Non-owner is forbidden
- **WHEN** a lecturer opens the management screen of an exam they neither created nor lead the class for
- **THEN** the system responds 403

#### Scenario: Missing exam is not leaked
- **WHEN** a lecturer requests an exam id that does not exist or is soft-deleted
- **THEN** the system responds 404

### Requirement: Create an exam with a question builder
The system SHALL let an owning lecturer create an exam at `/lecturer/tests/new` for a
class they lead, capturing: title, description/prompt, schedule (`start_at`, `end_at`),
`time_mode` (FIXED_WINDOW or INDIVIDUAL, with `duration_minutes` when INDIVIDUAL),
settings (`status` DRAFT/PUBLISHED/ARCHIVED, `passing_score`, `shuffle_questions`,
`shuffle_options`), and a set of MCQ/MR questions. Each question SHALL have content,
points, and two or more options with at least one marked correct; an MCQ SHALL have
exactly one correct option and an MR SHALL allow multiple. Saving SHALL persist the exam
with its questions and options and set `total_questions`.

#### Scenario: Valid exam is created
- **WHEN** a lecturer submits a new exam for a class they lead with valid MCQ/MR questions
- **THEN** the exam, its questions, and options are saved and `total_questions` matches the question count

#### Scenario: MCQ must have exactly one correct option
- **WHEN** a submitted MCQ question has zero or more than one correct option
- **THEN** the save is rejected with a field-level validation error and nothing is persisted

#### Scenario: Question needs at least two options and one correct
- **WHEN** a submitted question has fewer than two options or no correct option
- **THEN** the save is rejected with a validation error

### Requirement: Edit an exam
The system SHALL let an owning lecturer edit an exam at `/lecturer/tests/{id}/edit`,
pre-loading its current values and questions, and save a full replacement of the question
set (questions and options) together with the exam fields, re-deriving `total_questions`.

#### Scenario: Edit replaces the question set
- **WHEN** an owning lecturer saves the edit form after adding and removing questions
- **THEN** the persisted question set matches the submitted set exactly and `total_questions` is updated

### Requirement: Live monitoring
The system SHALL provide an owning lecturer a live-monitoring screen at
`/lecturer/tests/{id}/monitor` showing counts of submitted attempts, in-progress
attempts currently active (status IN_PROGRESS with `last_activity_at` within the last
~60 seconds), and the exam status, plus a per-enrolled-student list with each student's
state (not started / in progress / submitted) and last activity, and a countdown to
`end_at`. The screen SHALL refresh from a JSON endpoint about every 30 seconds without a
full page reload.

#### Scenario: In-progress student counts as active
- **WHEN** a student's attempt is IN_PROGRESS with `last_activity_at` 20 seconds ago
- **THEN** the monitor counts that student as currently active and shows their last activity

#### Scenario: Idle in-progress student is not active
- **WHEN** a student's attempt is IN_PROGRESS but `last_activity_at` is 5 minutes ago
- **THEN** the monitor does NOT count that student among the currently active

#### Scenario: Monitor data is served as JSON for refresh
- **WHEN** the monitor's periodic refresh calls the monitor data endpoint for an owned exam
- **THEN** the server returns the current counts and per-student states as JSON

### Requirement: Submissions overview
The system SHALL provide an owning lecturer a submissions screen at
`/lecturer/tests/{id}/submissions` with summary stats (submitted count, late count, exam
status) and a paginated, student-searchable list of attempts showing student name and
email, score / correct count, submit time, and that student's attempt count for the exam,
each linking to an attempt detail/review. For a FIXED_WINDOW exam, an attempt whose
`submitted_at` is after `end_at` SHALL be counted and marked late.

#### Scenario: Submissions list shows scored attempts
- **WHEN** an owning lecturer opens the submissions screen of an exam with graded attempts
- **THEN** each attempt row shows the student, their score/correct count, submit time, and attempt count

#### Scenario: Late submission is flagged
- **WHEN** a FIXED_WINDOW attempt was submitted after `end_at`
- **THEN** that attempt is included in the late count and marked late in the list

#### Scenario: Search narrows to a student
- **WHEN** the lecturer searches submissions by a student's name or email
- **THEN** only matching students' attempts are listed

