package com.ulp.features.flashcards.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.util.List;

/** View-model, form and API DTOs for the Flashcard feature (ULP-5.x). */
public final class FlashcardDtos {

    private FlashcardDtos() {
        // holder for records
    }

    /** Form payload for creating/editing a deck's metadata. */
    public record DeckForm(
            @NotBlank(message = "Tiêu đề không được để trống")
            @Size(max = 300, message = "Tiêu đề tối đa 300 ký tự")
            String title,
            @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
            String description
    ) {
        public static DeckForm empty() {
            return new DeckForm("", "");
        }
    }

    /**
     * A deck row on the list / class page.
     *
     * @param id         deck id
     * @param title      deck title
     * @param cardCount  number of cards in the deck
     * @param shared     whether the deck is SHARED
     * @param owner      whether the caller owns the deck
     * @param ownerName  owner's full name (shown on shared decks)
     * @param className  target class name (shown on shared decks); null otherwise
     */
    public record DeckSummary(
            Long id, String title, String description, long cardCount,
            boolean shared, boolean owner, String ownerName, String className
    ) {
    }

    /**
     * A card as rendered in the editor and study views (text-only).
     *
     * @param id    card id (null for a brand-new row)
     * @param front front text
     * @param back  back text
     */
    public record CardView(Long id, String front, String back) {
    }

    /** A submitted card item (bulk save); text-only. */
    public record CardItem(Long id, String front, String back) {
    }

    /** Bulk card-save request body. */
    public record SaveCardsRequest(List<CardItem> cards) {
    }

    /** Recall-rating request body (quality already mapped from the UI button). */
    public record ReviewRatingRequest(int quality) {
    }

    /** A single card row parsed from an imported Excel file (front/back text). */
    public record ImportedCardRow(String front, String back) {
    }

    /** Result of an Excel import: the parsed rows plus their count. */
    public record ImportResult(List<ImportedCardRow> cards, int count) {
    }

    /** A class the owner may share a deck to. */
    public record ClassOption(Long id, String name) {
    }

    /** Editor view-model: the deck plus its current cards + share targets. */
    public record DeckEditorView(Long deckId, String title, String description,
                                 List<CardView> cards, boolean shared, Long classId,
                                 List<ClassOption> shareClasses) {
    }

    /** Response returned after recording a Smart-Review rating. */
    public record ReviewResult(int dueRemaining, int intervalDays) {
    }

    /** Detail view-model for a single deck (launcher page). */
    public record DeckDetailView(Long id, String title, String description,
                                 long cardCount, boolean owner, boolean shared,
                                 Long classId, String className,
                                 List<ClassOption> shareClasses) {
    }

    /**
     * The two deck sections shown on the student list page. Only the "own decks"
     * section is paginated: {@code ownDecks} is one SSR page (newest-first) that
     * the numbered pager navigates via {@code ?page=N}. Shared decks are returned
     * in full (usually few) and never paginate.
     */
    public record StudentDeckList(Page<DeckSummary> ownDecks,
                                  List<DeckSummary> sharedDecks) {
    }
}
