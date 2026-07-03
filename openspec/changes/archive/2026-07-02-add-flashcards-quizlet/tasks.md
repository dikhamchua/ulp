## 1. Foundation (entities, repositories, access, constants)

- [x] 1.1 Create feature package `com.ulp.features.flashcards` with `controller/service/repository/entity/dto` subpackages
- [x] 1.2 Add JPA entities mapping existing tables: `FlashcardDeck`, `Flashcard`, `FlashcardReview` (no `@Data`; explicit getters + business helpers like `Comment`)
- [x] 1.3 Add repositories: decks by owner (non-deleted), decks SHARED by class ids, cards by deck ordered by `sort_order`, review by (user, card), due-cards query for a deck+user
- [x] 1.4 Add `DeckAccessResolver` returning OWNER / SHARED_MEMBER / NONE (SHARED = ACTIVE enrollment in deck `class_id`); 404 for NONE, 403 for owner-only by non-owner ← (verify: matches authz requirement in flashcard-decks spec; no existence leak)
- [x] 1.5 Add flashcard route/view/attr/message constants to `com.ulp.common.IConstant`

## 2. Phase 1 — Deck + card CRUD (text)

- [x] 2.1 `DeckService`: create deck (PRIVATE, owner), edit metadata, soft-delete, get-for-owner/get-for-caller via `DeckAccessResolver`
- [x] 2.2 `CardService`: bulk replace deck cards from submitted rows (full-replace in one tx), validate non-blank front/back, persist `sort_order` from row order
- [x] 2.3 DTOs (records) for deck summary, deck editor, card row — no entity leak
- [x] 2.4 `StudentFlashcardController` (`/my/flashcards`): new deck form + POST, deck detail, edit page; AJAX `POST /api/flashcards/{id}/cards` (bulk save)
- [x] 2.5 Editor template `templates/flashcards/deck-form.html` — Quizlet-style card rows (front|back), add/remove/reorder controls
- [x] 2.6 Editor JS `flashcard-deck-form.js` — add/remove/reorder rows + single submit orchestrator (`requestSubmit()`); feedback via `UlpToast`; inline field errors
- [x] 2.7 Editor CSS `flashcard-deck-form.css`
- [x] 2.8 Deck detail template (title/description/card count + study launchers placeholder) ← (verify: create → add cards → save → cards persist with order; blank title/side rejected inline; owner-only edit enforced)

## 3. Phase 2 — Card images (migration + deferred upload + magic-byte)

- [x] 3.1 Migration `V18__flashcard_images_and_review_unique.sql`: add `flashcards.front_image`/`back_image` VARCHAR(500) NULL; add `UNIQUE(user_id, flashcard_id)` on `flashcard_reviews`
- [x] 3.2 Add `frontImage`/`backImage` to `Flashcard` entity (matches post-V18 schema; Flyway `validate`)
- [x] 3.3 Image upload endpoint `POST /api/flashcards/{id}/card-image` with magic-byte validation (PNG/JPEG/GIF/WebP), storing via existing upload infra (reuse lesson-attachment validator pattern)
- [x] 3.4 Editor JS: per-card-side image select → preview only; on Save, upload all pending images first, then submit card set (deferred-upload-on-save rule; one orchestrator, await uploads before `requestSubmit()`)
- [x] 3.5 Render card images (front/back) in editor preview and study views via safe DOM (no innerHTML for user text) ← (verify: image uploads only on Save; non-image magic bytes rejected; path stored + rendered; deferred rule honored — one submit listener)

## 4. Phase 3 — Deck list + navigation

- [x] 4.1 `DeckService.listForStudent`: own non-deleted decks + decks SHARED to the student's ACTIVE-enrolled classes, each with card count
- [x] 4.2 Deck list page `templates/flashcards/list.html` (own + shared sections) + CSS
- [x] 4.3 Add "Thẻ ghi nhớ" item to the student header fragment → `/my/flashcards`
- [x] 4.4 Surface SHARED decks inside the class page (class detail) ← (verify: owner sees own decks; enrolled member sees shared deck; non-member does not; deleted excluded)

## 5. Phase 4 — Flip study mode

- [x] 5.1 `FlashcardStudyService.getStudyCards(deckId, userId)` via `DeckAccessResolver` (OWNER or SHARED_MEMBER)
- [x] 5.2 Flip route `/my/flashcards/{id}/flip` + template `flashcard-flip.html`
- [x] 5.3 Flip JS `flashcard-flip.js` — flip front/back, next/prev, shuffle, position counter (N/total), empty state; content via textContent + image render
- [x] 5.4 Flip CSS `flashcard-flip.css` ← (verify: flip toggles sides; next/prev + counter update; shuffle no repeat within pass; empty deck shows empty state; access enforced 404 for outsider)

## 6. Phase 5 — Smart Review (SM-2)

- [x] 6.1 `Sm2Scheduler` (pure): (quality, EF, reps, interval) → (EF, reps, interval, nextReviewAt); q<3 reset; 1→6→round(interval×EF); EF floor 1.30
- [x] 6.2 `SmartReviewService`: select due cards (no review row OR next_review_at ≤ now) per user; record rating → upsert single review row per (user, card)
- [x] 6.3 Review route `/my/flashcards/{id}/review` + AJAX `POST /api/flashcards/cards/{cardId}/review` (quality)
- [x] 6.4 Review template `flashcard-review.html` + JS `flashcard-review.js` (flip + 4 rating buttons Không nhớ/Khó/Tốt/Dễ → quality 1/3/4/5) + CSS
- [x] 6.5 Due-progress feedback (còn N thẻ đến hạn) ← (verify: new cards all due; q<3 resets to interval 1; success grows interval 1→6→×EF; EF≥1.30; one row per user-card; per-user isolation on shared deck)

## 7. Phase 6 — Share deck to class

- [x] 7.1 `DeckService.share(deckId, ownerId, classId)` / `unshare` — PRIVATE↔SHARED + set/clear `class_id`; owner-only (403 otherwise); class must be one the owner belongs to
- [x] 7.2 Share control in deck detail (choose class, toggle shared) + JS/UlpToast
- [x] 7.3 Shared decks visible to class ACTIVE members (list + class page), not editable by them ← (verify: share → visible to enrolled member; member cannot edit (403); non-owner cannot change sharing (403); unshare hides it)

## 8. Tests

- [x] 8.1 `Sm2SchedulerTest` (unit): reset on q<3, interval progression, EF floor
- [x] 8.2 `DeckServiceTest` / `CardServiceTest` (@SpringBootTest @Transactional): create/edit/delete, bulk card save + validation, authz (owner/shared/outsider), share/unshare
- [x] 8.3 `SmartReviewServiceTest`: due selection, rating upsert, scheduling, per-user isolation
- [x] 8.4 `FlashcardControllerTest` / study + review controller tests (MockMvc @WithUserDetails): routes, 404/403 authz, AJAX card-save + review + image-upload validation
- [x] 8.5 Run full suite green (`mvnw test`) ← (verify: all new tests pass and no regression across the suite)

## 9. Issue tracking & wrap-up

- [ ] 9.1 Flip labels status:todo→done and close per story as completed: #50 (+#188-191), #51 (+#192-193), #52 (+#194-197), #53 (+#198-199)
- [ ] 9.2 Close Epic #10 after all four stories done
