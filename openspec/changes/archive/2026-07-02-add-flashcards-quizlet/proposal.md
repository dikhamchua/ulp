## Why

Students need a self-study tool to memorise course material. The Sprint 4 Flashcard epic (ULP-5.x) delivers a Quizlet-style experience: students build their own decks of two-sided cards and study them with flip-through and spaced-repetition (SM-2) modes. The database schema (`flashcard_decks`, `flashcards`, `flashcard_reviews`) already exists from V1 but is unused; this change wires it into a complete end-to-end feature and adds image support.

## What Changes

- Add a student-facing **"Thẻ ghi nhớ"** area to create, edit, list and delete personal flashcard decks.
- **Quizlet-style card editor**: multi-row term/definition editor, add/remove/reorder rows, save all cards in one submit.
- **Images on cards**: each card side (front/back) may carry an optional image, uploaded on save (deferred-upload) with magic-byte validation. **BREAKING (schema)**: new columns `flashcards.front_image`, `flashcards.back_image`.
- **Study — Flip mode**: flip front/back, next/prev, shuffle, progress counter.
- **Study — Smart Review (SM-2)**: surface cards due today, rate recall (Không nhớ / Khó / Tốt / Dễ), update the SM-2 schedule per user-card.
- **Sharing**: a deck can move from PRIVATE to SHARED to one of the owner's classes; enrolled classmates can view/study (not edit) it, and shared decks surface inside the class page.
- **Migration V18**: add image columns to `flashcards`; add `UNIQUE(user_id, flashcard_id)` to `flashcard_reviews` (one SM-2 state row per user-card).

Out of scope this change: "star difficult cards", Test/Quiz auto-generation, Match game, lecturer OFFICIAL decks, AI generation (Sprint 8).

## Capabilities

### New Capabilities
- `flashcard-decks`: create/edit/delete flashcard decks and their cards (text + optional images), list own and shared decks, share a deck to a class, and the authorization rules governing who may view/study/edit a deck.
- `flashcard-study`: study a deck via flip-through mode and via SM-2 spaced-repetition Smart Review, including due-card selection, recall rating, and per-user review scheduling.

### Modified Capabilities
<!-- None: no existing capability's requirements change. -->

## Impact

- **Schema**: new migration `V18` (image columns on `flashcards`; unique constraint on `flashcard_reviews`). Flyway stays in `validate` mode.
- **New feature package**: `com.ulp.features.flashcards` (controller/service/repository/entity/dto).
- **New entities**: `FlashcardDeck`, `Flashcard`, `FlashcardReview` mapping existing tables.
- **Upload**: reuses the existing upload storage + magic-byte validation pattern from the lesson-attachment feature; adds an image-upload endpoint for cards.
- **Navigation**: adds a top-level "Thẻ ghi nhớ" item to the student header; surfaces shared decks in the class page.
- **Templates/static**: new Thymeleaf views under `templates/flashcards/`, new CSS/JS per feature.
- **Issues**: Epic #10; stories #50 (ULP-5.1), #51 (ULP-5.2), #52 (ULP-5.3), #53 (ULP-5.4); subtasks #188–199.
- No impact on existing features; additive only.
