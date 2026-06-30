## ADDED Requirements

### Requirement: Create a lesson inside a section

A lecturer who owns the class containing a section SHALL be able to
create a new lesson inside that section by submitting a form with
title, status, and rich-text body. The lesson is appended to the
section's existing order. HEAD and ADMIN roles MUST also be able to
create lessons in any class; LECTURER role MUST be rejected with
HTTP 403 when attempting to create a lesson in a class they do not own.

#### Scenario: Lecturer creates a lesson with valid input
- **GIVEN** a class `C` owned by lecturer `L` containing section `S`
- **WHEN** `L` submits a create form with title `"Bài 1 — Giới thiệu"`,
  status `DRAFT`, and a 200-character HTML body
- **THEN** the system persists a lesson with the given title, status,
  the sanitised body, and `display_order = max(existing) + 1`
- **AND** redirects to the lessons list with a flash success message
- **AND** writes a `CREATED` row to `activity_lessons` referencing the
  new lesson and the actor

#### Scenario: Blank title is rejected with inline error
- **GIVEN** the create form is open
- **WHEN** the lecturer submits a blank title
- **THEN** the system re-renders the form with a binding error on the
  title field and no lesson is persisted

#### Scenario: Title over 300 characters is rejected
- **GIVEN** the create form is open
- **WHEN** the lecturer submits a 301-character title
- **THEN** the system re-renders the form with a length-validation
  error and no lesson is persisted

#### Scenario: A non-owning lecturer is rejected with 403
- **GIVEN** section `S` belongs to class `C` owned by lecturer `L1`
- **WHEN** lecturer `L2` POSTs the create endpoint for `S`
- **THEN** the system returns HTTP 403 and persists nothing

### Requirement: Edit a lesson's title, status, and body

A lecturer who owns the containing class SHALL be able to edit a
lesson's title, status, and rich-text body. The edit MUST be idempotent
on its own keys — re-submitting the same form produces the same state
with no audit-log entry when nothing changed.

#### Scenario: Lecturer renames a lesson
- **GIVEN** an existing lesson with title `"Cũ"`
- **WHEN** the lecturer submits the edit form with title `"Mới"`
- **THEN** the lesson's title is `"Mới"`
- **AND** an `UPDATED` activity row is written with metadata
  `{"old":"Cũ","new":"Mới"}`

#### Scenario: Lecturer rewrites lesson content
- **GIVEN** an existing lesson
- **WHEN** the lecturer submits the edit form with new HTML body
  containing `<script>alert(1)</script><p>OK</p>`
- **THEN** the persisted body contains `<p>OK</p>` but no `<script>`
- **AND** an `UPDATED` activity row is written

#### Scenario: Re-submitting unchanged form does not pollute history
- **GIVEN** an existing lesson with title `"X"` and body `<p>Hello</p>`
- **WHEN** the lecturer re-submits the same title and body
- **THEN** no `UPDATED` activity row is written

### Requirement: Publish and unpublish a lesson

A lecturer who owns the containing class SHALL be able to toggle a
lesson's status between `DRAFT` and `PUBLISHED` without rewriting the
body. Status transitions MUST be auditable as their own activity
types.

#### Scenario: Lecturer publishes a draft lesson
- **GIVEN** a lesson with status `DRAFT`
- **WHEN** the lecturer triggers publish
- **THEN** the lesson's status is `PUBLISHED`
- **AND** a `PUBLISHED` activity row is written

#### Scenario: Lecturer unpublishes a published lesson
- **GIVEN** a lesson with status `PUBLISHED`
- **WHEN** the lecturer triggers unpublish
- **THEN** the lesson's status is `DRAFT`
- **AND** an `UNPUBLISHED` activity row is written

### Requirement: Soft-delete a lesson

A lecturer who owns the containing class SHALL be able to soft-delete a
lesson. The deleted lesson MUST disappear from default queries but its
audit history MUST remain intact, and its previous `display_order` slot
MUST be free for another lesson to claim.

#### Scenario: Lecturer deletes a lesson
- **GIVEN** an existing lesson with `display_order = 2`
- **WHEN** the lecturer triggers delete
- **THEN** the lesson's `is_deleted` is `1` and `display_order` is
  cleared to `NULL`
- **AND** the lesson no longer appears in
  `findBySectionIdOrderByDisplayOrderAsc`
- **AND** a `DELETED` activity row is written

#### Scenario: Recreate after delete does not collide with unique key
- **GIVEN** a section had three lessons with orders 0, 1, 2 and the
  one at order `2` was deleted
- **WHEN** the lecturer creates a new lesson in the same section
- **THEN** the new lesson is persisted with `display_order = 2`
- **AND** no `DataIntegrityViolationException` is raised

### Requirement: Reorder lessons within a section

A lecturer who owns the containing class SHALL be able to submit a new
ordering for the lessons of a section. The submitted list MUST be a
permutation of the section's live lessons; any mismatch (extra, missing,
or unknown id) MUST be rejected with HTTP 400.

#### Scenario: Lecturer reorders three lessons
- **GIVEN** lessons `A`, `B`, `C` with orders `0, 1, 2`
- **WHEN** the lecturer posts the ordered ids `[C, A, B]`
- **THEN** the lessons end with orders `A=1, B=2, C=0`
- **AND** a `REORDERED` activity row is written for every lesson whose
  position actually changed (not for lessons that stayed in place)

#### Scenario: Reorder with stale ids is rejected
- **GIVEN** a section currently has lessons `[A, B]`
- **WHEN** the lecturer posts the ordered ids `[A]` (missing `B`) or
  `[A, B, 999]` (unknown id)
- **THEN** the system returns HTTP 400 with a user-facing message
- **AND** no lesson order changes

### Requirement: Rich-text body is sanitised before persistence

The system SHALL strip script tags, event handlers, and unsafe URL
schemes from any HTML submitted to the create or edit endpoints. The
sanitiser MUST allow heading, paragraph, list, link, image, blockquote,
preformatted, and inline emphasis tags as specified in the design.

#### Scenario: Script tag is stripped
- **WHEN** a lecturer submits body `<p>Safe</p><script>alert(1)</script>`
- **THEN** the persisted body is `<p>Safe</p>` with the script removed

#### Scenario: Event handler attribute is stripped
- **WHEN** a lecturer submits body `<p onclick="evil()">Click</p>`
- **THEN** the persisted body is `<p>Click</p>` with the handler removed

#### Scenario: Data-URI image is preserved
- **WHEN** a lecturer submits body
  `<p>See <img src="data:image/png;base64,iVBORw0KGgo..." alt="x"></p>`
- **THEN** the persisted body still contains the data-URI image

#### Scenario: javascript: URL is dropped
- **WHEN** a lecturer submits body `<a href="javascript:alert(1)">x</a>`
- **THEN** the persisted body removes the unsafe `href`

### Requirement: Lessons tab content column shows lessons of the selected section

When the lecturer selects a section in the folders column of the
lessons tab, the content column SHALL render the list of that section's
lessons together with their status pill and an action menu. The empty
state MUST distinguish between "no section selected" and "section
selected but empty".

#### Scenario: Lessons of the selected section are shown
- **GIVEN** the lecturer is on `/lecturer/classes/{id}/lessons?section={sid}`
  and the section has two lessons
- **THEN** the page renders both lesson titles and their status pills

#### Scenario: Selected section has no lessons
- **GIVEN** the section currently has zero lessons
- **THEN** the page renders an empty state with a call to action "Tạo
  bài giảng" pointing at the create form

#### Scenario: No section selected — "Tất cả bài giảng"
- **GIVEN** the lecturer is on `/lecturer/classes/{id}/lessons` (no
  `?section=` parameter)
- **THEN** the page renders an empty placeholder explaining that the
  lecturer should pick a section to manage its lessons
