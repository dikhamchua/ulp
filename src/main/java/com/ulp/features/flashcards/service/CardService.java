package com.ulp.features.flashcards.service;

import com.ulp.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckEditorView;
import com.ulp.features.flashcards.entity.Flashcard;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.MSG_CARD_SIDE_BLANK;

/**
 * Card bulk-save (full replace) + editor read (ULP-5.x).
 *
 * <p>The editor submits the whole card list; {@link #replaceCards} diffs it
 * against the deck's current cards in one transaction: kept ids are updated
 * in place (preserving their SM-2 review schedule), new rows are inserted, and
 * removed cards are deleted (their reviews cascade away).
 */
@Service
public class CardService {

    private final FlashcardRepository cardRepository;
    private final DeckAccessResolver accessResolver;

    public CardService(FlashcardRepository cardRepository,
                       DeckAccessResolver accessResolver) {
        this.cardRepository = cardRepository;
        this.accessResolver = accessResolver;
    }

    /** Editor view-model (owner-only): deck metadata + current cards. */
    @Transactional(readOnly = true)
    public DeckEditorView getEditorView(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        List<CardView> cards = new ArrayList<>();
        for (Flashcard c : cardRepository.findByDeckIdOrderBySortOrderAsc(deckId)) {
            cards.add(toView(c));
        }
        return new DeckEditorView(deck.getId(), deck.getTitle(), deck.getDescription(),
                cards, deck.isShared(), deck.getClassId(), List.of());
    }

    /**
     * Replaces the deck's cards with the submitted set; owner-only. Validates
     * that every card has non-blank front AND back text; a blank side aborts
     * the whole save (existing cards left unchanged by the rolled-back tx).
     *
     * @throws IllegalArgumentException with {@code MSG_CARD_SIDE_BLANK} on a blank side
     */
    @Transactional
    public void replaceCards(Long deckId, Long ownerId, List<CardItem> items) {
        accessResolver.requireOwner(deckId, ownerId);
        // Validate ALL rows before any mutation so a blank side leaves the
        // existing cards untouched (atomic even when joined to an outer tx).
        for (CardItem item : items) {
            if (trim(item.front()).isEmpty() || trim(item.back()).isEmpty()) {
                throw new IllegalArgumentException(MSG_CARD_SIDE_BLANK);
            }
        }

        List<Flashcard> existing = cardRepository.findByDeckIdOrderBySortOrderAsc(deckId);
        Map<Long, Flashcard> byId = new HashMap<>();
        for (Flashcard c : existing) byId.put(c.getId(), c);

        Set<Long> keptIds = new HashSet<>();
        int order = 0;
        for (CardItem item : items) {
            String front = trim(item.front());
            String back = trim(item.back());
            if (item.id() != null && byId.containsKey(item.id())) {
                Flashcard card = byId.get(item.id());
                card.update(front, back, order);
                cardRepository.save(card);
                keptIds.add(item.id());
            } else {
                cardRepository.save(new Flashcard(deckId, front, back, order));
            }
            order++;
        }
        for (Flashcard c : existing) {
            if (!keptIds.contains(c.getId())) {
                cardRepository.delete(c);
            }
        }
    }

    private static CardView toView(Flashcard c) {
        return new CardView(c.getId(), c.getFrontText(), c.getBackText());
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
