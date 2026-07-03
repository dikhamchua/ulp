## ADDED Requirements

### Requirement: Flip study mode
A student with access to a deck SHALL study it by flipping cards. The mode shows one card at a time, flips between front and back, moves to the next/previous card, shuffles the order, and shows a position counter (e.g. 3/20).

#### Scenario: Flip a card
- **WHEN** the student clicks/taps a card (or presses space) in flip mode
- **THEN** the card toggles between its front and back content (text and image if present)

#### Scenario: Navigate and shuffle
- **WHEN** the student moves next/previous or shuffles
- **THEN** the shown card and the position counter update accordingly, and shuffle randomises the remaining order without repeating cards within a pass

#### Scenario: Empty deck
- **WHEN** the student opens flip mode on a deck with no cards
- **THEN** the system shows an empty state instead of a card

### Requirement: Smart Review selects due cards
Smart Review SHALL present the cards of a deck that are due for the current user, where a card is due when it has no review row yet or its `next_review_at` is at or before now. Cards not yet due are not presented in the current session.

#### Scenario: New cards are due
- **WHEN** a student starts Smart Review on a deck they have never reviewed
- **THEN** every card is presented as due

#### Scenario: Scheduled card not yet due is skipped
- **WHEN** a card's `next_review_at` is in the future for the student
- **THEN** that card is not presented in the current Smart Review session

### Requirement: Recall rating updates the SM-2 schedule
When the student rates recall on a card (Không nhớ / Khó / Tốt / Dễ mapped to quality 1/3/4/5), the system SHALL update that user-card's SM-2 state (easiness factor, repetitions, interval) and compute the next due date, storing exactly one review row per user-card.

#### Scenario: Poor recall resets the schedule
- **WHEN** the student rates a card "Không nhớ" (quality below 3)
- **THEN** repetitions reset to 0 and interval to 1 day, and `next_review_at` is set to one day from now

#### Scenario: Successful recall lengthens the interval
- **WHEN** the student rates a card "Tốt"/"Dễ" on successive reviews
- **THEN** repetitions increase and the interval grows per SM-2 (1 → 6 → round(interval × EF)), with the easiness factor adjusted and never below 1.30

#### Scenario: One review row per user-card
- **WHEN** the student rates the same card more than once
- **THEN** the system upserts the single review row for that (user, card), not a new row each time

### Requirement: Per-user review state
Review scheduling SHALL be independent per user: one student's ratings on a shared deck MUST NOT affect another student's due schedule for the same cards.

#### Scenario: Two students study the same shared deck
- **WHEN** two enrolled students review the same shared deck
- **THEN** each has their own SM-2 schedule and due sets, isolated from the other
