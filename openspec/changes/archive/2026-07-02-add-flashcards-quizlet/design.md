## Context

The V1 schema already defines `flashcard_decks`, `flashcards`, and `flashcard_reviews` (SM-2 columns) but no code uses them. This change builds the full student-facing Flashcard feature on top of them, Quizlet-style. Stack: Spring Boot 3.4.4 + Thymeleaf (SSR) + MySQL 8 + Flyway (`validate`). Existing patterns to mirror: lesson feature (feature-first package, DTO records, `LessonAccessResolver` authz), lesson-attachment upload (deferred-upload-on-save + magic-byte validation), `UlpToast` notifications, `IConstant` static-import constants.

## Goals / Non-Goals

**Goals:**
- Deck + card CRUD with a single-submit editor, text + optional images.
- Two study modes: flip-through and SM-2 Smart Review, per-user scheduling.
- PRIVATE→SHARED-to-class sharing with correct authorization.
- Reuse existing schema (only additive migration) and existing upload/auth patterns.

**Non-Goals:**
- Star/favourite cards, Test/Quiz auto-gen, Match game, lecturer OFFICIAL decks, AI generation. Rich text / audio on cards. Deck import/paste.

## Decisions

- **Package**: `com.ulp.features.flashcards` with `controller/`, `service/`, `repository/`, `entity/`, `dto/`. Entities `FlashcardDeck`, `Flashcard`, `FlashcardReview` map the existing tables (no `@Data`; explicit getters + business helpers, like `Comment`).
- **Card editing = full replace on save**: the editor submits the whole card list; the service diffs/replaces the deck's cards in one transaction (simplest correct model for a personal editor). Rows carry `sort_order` from their DOM order.
- **Images = deferred upload**: selecting an image in the editor only previews it client-side; on Save, a single submit orchestrator (`requestSubmit()`, per `deferred-upload-on-save.md`) uploads pending images first, then posts the card set. Server stores files via the existing upload storage (UPLOAD_DIR) and validates by magic bytes (PNG/JPEG/GIF/WebP), mirroring the lesson-attachment validator. Card columns store the relative path.
- **SM-2 in a dedicated helper** (`Sm2Scheduler`, pure function, ~40 lines): input (quality, prior EF/reps/interval) → new (EF/reps/interval/nextReviewAt). Kept separate so it is unit-testable without the DB. Rating→quality map: Không nhớ=1, Khó=3, Tốt=4, Dễ=5. `flashcard_reviews` holds one row per (user, card), upserted each review (migration adds the UNIQUE constraint to enforce this).
- **Due selection query**: cards of a deck LEFT JOIN the caller's review rows; due = no row OR `next_review_at <= now`. One query, no N+1.
- **Authorization** via a small `DeckAccessResolver` (mirrors `LessonAccessResolver`): resolves a deck for a caller and returns its access level (OWNER / SHARED_MEMBER / NONE). Owner-only mutations check OWNER (else 403); read/study checks OWNER or SHARED_MEMBER (else 404 so existence isn't leaked). SHARED membership = ACTIVE enrollment in the deck's `class_id`.
- **Navigation**: add "Thẻ ghi nhớ" to the student header fragment → `/my/flashcards`. Shared decks also listed inside the class page (reuse the class detail shell). Deck routes: `/my/flashcards` (list), `/my/flashcards/new`, `/my/flashcards/{id}` (detail/study launcher), `/my/flashcards/{id}/edit`, study modes at `/my/flashcards/{id}/flip` and `/my/flashcards/{id}/review`; AJAX under `/api/flashcards/**` for card save, image upload, and review submit.
- **File-size discipline**: split services by concern — `DeckService` (CRUD/list/share), `CardService` (bulk save + images), `FlashcardStudyService` (flip fetch), `SmartReviewService` (due + rating), `DeckAccessResolver`, `Sm2Scheduler` — each well under ~200 lines.

## Risks / Trade-offs

- **Full-replace card save loses per-card review history when a card is deleted** → acceptable: `flashcard_reviews` FK `ON DELETE CASCADE` cleans orphans; editing text of a kept card preserves its id and schedule.
- **Deferred multi-image upload before submit is fiddly (multiple pending files)** → follow the canonical single-orchestrator pattern exactly (await all uploads, then `requestSubmit()`); one submit listener only, no double-submit.
- **Image storage growth / orphaned files on delete** → out of scope to garbage-collect now; soft-deleted decks keep files. Documented limitation.
- **SM-2 correctness is easy to get subtly wrong** → isolate in `Sm2Scheduler` with unit tests covering q<3 reset, 1→6→×EF progression, EF floor 1.30.
- **Sharing leak** → all list/study paths must go through `DeckAccessResolver`; tests assert outsider→404 and shared-member→can-study / cannot-edit.

## Migration Plan

- **V18** (`V18__flashcard_images_and_review_unique.sql`): `ALTER TABLE flashcards ADD COLUMN front_image VARCHAR(500) NULL, ADD COLUMN back_image VARCHAR(500) NULL;` and `ALTER TABLE flashcard_reviews ADD CONSTRAINT uq_fr_user_card UNIQUE (user_id, flashcard_id);` (utf8mb4/InnoDB defaults inherited). Existing rows: none in dev; the unique constraint is safe on empty data.
- Flyway stays `validate`; entities must match the post-V18 schema exactly.
- Rollback: drop the two columns + the unique constraint (new migration if ever needed). No data backfill required.

## Open Questions

- None — scope locked with the user.
