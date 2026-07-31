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
     * @param id          deck id
     * @param title       deck title
     * @param cardCount   number of cards in the deck
     * @param shared      whether the deck targets at least one class
     * @param owner       whether the caller owns the deck
     * @param ownerName   owner's full name (shown on shared decks)
     * @param classNames  names of every class the deck targets; empty when private
     */
    public record DeckSummary(
            Long id, String title, String description, long cardCount,
            boolean shared, boolean owner, String ownerName, List<String> classNames
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
                                 List<CardView> cards, boolean shared,
                                 List<ClassOption> targetClasses,
                                 List<ClassOption> shareClasses) {
    }

    /** Response returned after recording a Smart-Review rating. */
    public record ReviewResult(int dueRemaining, int intervalDays) {
    }

    /**
     * Detail view-model for a single deck (launcher page).
     *
     * @param shared        whether the deck targets at least one class
     * @param targetClasses every class the deck currently targets (id + name)
     * @param shareClasses  classes the owner may pick from; empty for non-owners
     * @param memberCount   distinct people reached across every target class
     * @param publicLink    whether the public link is switched on
     * @param shareToken    the public-link token; null until first enabled, and
     *                      only ever populated for the owner
     */
    public record DeckDetailView(Long id, String title, String description,
                                 long cardCount, boolean owner, boolean shared,
                                 List<ClassOption> targetClasses,
                                 List<ClassOption> shareClasses,
                                 long memberCount,
                                 boolean publicLink,
                                 String shareToken) {
    }

    /** Anonymous view of a deck reached by public link; owner and classes omitted. */
    public record PublicDeckView(String title, String description, long cardCount) {
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
