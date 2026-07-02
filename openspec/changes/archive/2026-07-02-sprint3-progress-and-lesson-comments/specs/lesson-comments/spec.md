# Spec: lesson-comments

## ADDED Requirements

### Requirement: Comment access control
All comment endpoints under `/api/lessons/{lessonId}/comments` SHALL require authentication and SHALL authorize in the service layer: the caller MUST be an ACTIVE-enrolled student of the lesson's class OR the class's owning lecturer. The lesson MUST pass the standard gates (live class, section belongs to class, lesson PUBLISHED and not soft-deleted). Any authz or existence failure SHALL respond 404 with the shared JSON failure envelope so lesson existence is never leaked.

#### Scenario: Enrolled student allowed
- **WHEN** an ACTIVE-enrolled student calls GET on a published lesson's comments
- **THEN** the response is 200 with the comment list

#### Scenario: Owning lecturer allowed
- **WHEN** the class's lecturer calls GET on the same endpoint
- **THEN** the response is 200

#### Scenario: Outsider denied
- **WHEN** a user with no enrollment (and not the owning lecturer) calls any comment endpoint
- **THEN** the response is 404

#### Scenario: DRAFT lesson hidden
- **WHEN** any caller targets a DRAFT lesson's comments
- **THEN** the response is 404

### Requirement: List lesson comments
GET `/api/lessons/{lessonId}/comments` SHALL return the lesson's comments as JSON: root comments (parent null) ordered oldest-first, each carrying its replies oldest-first. Only rows with `moderation_status = 'APPROVED'` are returned. Soft-deleted roots that still have at least one live reply SHALL be returned with a `deleted: true` flag and no content; soft-deleted roots without live replies and soft-deleted replies SHALL be omitted. Each item SHALL include: id, parentId, authorId, authorName, a lecturer flag (author is the class's owning lecturer), content, edited flag, createdAt, plus per-item booleans `canEdit` and `canDelete` computed for the caller.

#### Scenario: Threaded ordering
- **WHEN** a lesson has two root comments and a reply on the first
- **THEN** the JSON lists roots oldest-first and the reply nested under its root

#### Scenario: Deleted root with replies
- **WHEN** a root comment was soft-deleted but has a live reply
- **THEN** the root appears flagged deleted (placeholder rendering) and the reply remains visible

#### Scenario: Deleted leaf omitted
- **WHEN** a reply was soft-deleted
- **THEN** it does not appear in the response

### Requirement: Create comment or reply
POST `/api/lessons/{lessonId}/comments` with JSON `{content, parentId?}` SHALL create a comment. Content is plain text: trimmed, 1–2000 characters after trim; blank or over-limit content SHALL respond 400 with a Vietnamese message. When `parentId` is present it MUST reference a live, APPROVED comment of the SAME lesson, otherwise 400; replying to a reply SHALL attach to that reply's root (1-level threading). The created row is returned as JSON.

#### Scenario: Create root question
- **WHEN** an enrolled student POSTs valid content without parentId
- **THEN** a root comment row is persisted and returned with `canEdit`/`canDelete` true for the author

#### Scenario: Reply to a reply flattens
- **WHEN** a caller POSTs with parentId pointing at an existing reply
- **THEN** the stored comment's parent is that reply's root comment

#### Scenario: Blank content rejected
- **WHEN** content is empty or whitespace-only
- **THEN** the response is 400 and nothing is persisted

#### Scenario: Cross-lesson parent rejected
- **WHEN** parentId references a comment belonging to a different lesson
- **THEN** the response is 400 and nothing is persisted

### Requirement: Edit own comment
PUT `/api/lessons/{lessonId}/comments/{commentId}` with JSON `{content}` SHALL update the comment's content only when the caller is its author. It SHALL apply the same content validation as creation, set `is_edited = 1`, and return the updated row. Editing another user's comment SHALL respond 403; editing a soft-deleted or missing comment SHALL respond 404.

#### Scenario: Author edits
- **WHEN** the author PUTs new valid content
- **THEN** the row's content changes, `is_edited` becomes true, and the JSON reflects both

#### Scenario: Non-author edit denied
- **WHEN** a different enrolled student PUTs to that comment
- **THEN** the response is 403 and content is unchanged

### Requirement: Delete comment
DELETE `/api/lessons/{lessonId}/comments/{commentId}` SHALL soft-delete (`is_deleted = 1`) when the caller is the comment's author OR the class's owning lecturer. Other callers SHALL receive 403. Deleting an already-deleted or missing comment SHALL respond 404. Soft-deleting a root SHALL NOT cascade-delete its replies.

#### Scenario: Author deletes own
- **WHEN** the author DELETEs their comment
- **THEN** the row is soft-deleted and the API responds with the success envelope

#### Scenario: Lecturer deletes any
- **WHEN** the owning lecturer DELETEs a student's comment
- **THEN** the row is soft-deleted

#### Scenario: Other student denied
- **WHEN** an enrolled student DELETEs another student's comment
- **THEN** the response is 403 and the row remains live

### Requirement: Comments panel on the student lessons page
When a lesson is inlined on the student lessons page, the page SHALL render a "Thảo luận" panel below the lesson content that: loads the list via AJAX on page load; renders all user content via `textContent` (never innerHTML) with newlines preserved by CSS; shows author name, "GV" badge for the lecturer, "(đã chỉnh sửa)" for edited rows, and timestamps; offers a composer (textarea + submit) for new questions, a reply action per root, and edit/delete actions only on rows where the API returned `canEdit`/`canDelete`. Deleting asks for confirmation. All mutations send the CSRF header read from the `_csrf` meta tags, refresh the panel on success, and report failures via `UlpToast.error`; the panel SHALL show a Vietnamese empty state when there are no comments and a non-blocking error state when loading fails.

#### Scenario: XSS-safe rendering
- **WHEN** a comment containing `<script>` markup is listed
- **THEN** the markup renders as literal text and no script executes

#### Scenario: Empty state
- **WHEN** the lesson has no comments
- **THEN** the panel shows the Vietnamese empty-state message with the composer available

#### Scenario: Failed mutation surfaces toast
- **WHEN** a create/edit/delete request fails (network or 4xx/5xx)
- **THEN** an `UlpToast.error` toast appears and the panel keeps its previous content
