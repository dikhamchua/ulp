# flashcard-decks Specification

## Purpose
TBD - created by archiving change add-flashcards-quizlet. Update Purpose after archive.
## Requirements
### Requirement: Create a flashcard deck
An authenticated student SHALL create a personal flashcard deck with a title and optional description. A new deck is PRIVATE and owned by its creator.

#### Scenario: Create deck with title
- **WHEN** a student submits the new-deck form with a non-blank title
- **THEN** the system persists a deck owned by the student with visibility PRIVATE and returns/redirects to its editor

#### Scenario: Reject blank title
- **WHEN** a student submits the new-deck form with a blank title
- **THEN** the system rejects it with an inline validation error and does not persist a deck

### Requirement: Edit deck cards in one save
The deck editor SHALL let the owner add, edit, remove and reorder cards as rows (front text / back text) and persist the whole set in a single save. Each card requires non-blank front and back text.

#### Scenario: Save multiple cards
- **WHEN** the owner saves the editor with several card rows
- **THEN** the system replaces the deck's cards with the submitted rows, preserving their order in `sort_order`

#### Scenario: Reject a card with empty side
- **WHEN** the owner saves a card row whose front or back text is blank
- **THEN** the system rejects the save with an inline error and leaves existing cards unchanged

### Requirement: Optional image on each card side
Each card side (front and back) SHALL support an optional image. Images are selected in the editor but uploaded only when the owner saves the deck (deferred upload). Uploads MUST be validated by magic bytes and only common image types (PNG/JPEG/GIF/WebP) are accepted.

#### Scenario: Attach an image on save
- **WHEN** the owner selects an image for a card side and then saves
- **THEN** the file is uploaded, its stored path is written to `front_image`/`back_image`, and the image renders on that card

#### Scenario: Reject a non-image file
- **WHEN** the owner selects a file whose magic bytes are not a supported image type and saves
- **THEN** the system rejects the upload with an error and does not persist an image path for that card

### Requirement: List own and shared decks
The student SHALL see, under "Thẻ ghi nhớ", the decks they own plus decks SHARED to a class they are ACTIVE-enrolled in. Deleted decks are excluded.

#### Scenario: Owner sees their decks
- **WHEN** a student opens "Thẻ ghi nhớ"
- **THEN** the list shows every non-deleted deck they own, each with its card count

#### Scenario: Enrolled member sees a shared deck
- **WHEN** another student is ACTIVE-enrolled in the class a deck is SHARED to
- **THEN** that deck appears in their list marked as shared, and it does not appear for non-members

### Requirement: Share a deck to a class
The owner SHALL change a deck from PRIVATE to SHARED targeting one of their classes, and revert it to PRIVATE. Only the owner may change sharing.

#### Scenario: Share to a class
- **WHEN** the owner sets a deck to SHARED with a target class
- **THEN** the deck's visibility becomes SHARED and its `class_id` is the target class, making it visible to that class's ACTIVE members

#### Scenario: Non-owner cannot change sharing
- **WHEN** a non-owner attempts to change a deck's sharing
- **THEN** the system denies it with 403

### Requirement: Delete a deck
The owner SHALL soft-delete a deck they own; a deleted deck disappears from all lists and can no longer be studied.

#### Scenario: Owner deletes deck
- **WHEN** the owner deletes their deck
- **THEN** the deck is soft-deleted and no longer returned in any list or study view

### Requirement: Deck access authorization
Access SHALL be governed by ownership and visibility: the owner has full CRUD; an ACTIVE-enrolled member of a SHARED deck's class may view and study but not edit; a PRIVATE deck is accessible only to its owner. Inaccessible decks MUST return 404 (existence not leaked); owner-only actions attempted by a non-owner MUST return 403.

#### Scenario: Outsider cannot open a private deck
- **WHEN** a student who is neither owner nor an eligible shared-class member requests a deck
- **THEN** the system returns 404

#### Scenario: Shared member cannot edit
- **WHEN** an ACTIVE-enrolled member of a SHARED deck's class attempts to edit its cards
- **THEN** the system returns 403 and the deck is unchanged

