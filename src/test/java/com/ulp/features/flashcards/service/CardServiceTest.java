package com.ulp.features.flashcards.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for {@link CardService}: bulk save, validation, authz. */
@SpringBootTest
@Transactional
class CardServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User other;
    private Long deckId;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        other = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();
        deckId = deckService.createDeck(owner.getId(), new DeckForm("Bộ thẻ", null));
    }

    @Test
    void bulk_save_persists_cards_in_order() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "front A", "back A"),
                new CardItem(null, "front B", "back B")));

        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).front()).isEqualTo("front A");
        assertThat(cards.get(1).back()).isEqualTo("back B");
    }

    @Test
    void blank_side_rejected_and_leaves_existing_unchanged() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "keep", "keep back")));

        assertThatThrownBy(() -> cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "ok", "ok"),
                new CardItem(null, "   ", "missing front"))))
                .isInstanceOf(IllegalArgumentException.class);

        // The original single card survives the aborted save.
        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).front()).isEqualTo("keep");
    }

    @Test
    void editing_kept_card_preserves_its_id() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "orig", "orig back")));
        Long cardId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();

        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(cardId, "edited", "edited back")));

        List<CardView> cards = cardService.getEditorView(deckId, owner.getId()).cards();
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo(cardId);
        assertThat(cards.get(0).front()).isEqualTo("edited");
    }

    @Test
    void non_owner_save_denied() {
        assertThatThrownBy(() -> cardService.replaceCards(deckId, other.getId(), List.of(
                new CardItem(null, "x", "y"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void removed_card_is_deleted() {
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "a", "a"),
                new CardItem(null, "b", "b")));
        Long keepId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();

        // Save only the first card → the second is dropped.
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(keepId, "a", "a")));

        assertThat(cardService.getEditorView(deckId, owner.getId()).cards()).hasSize(1);
    }
}
