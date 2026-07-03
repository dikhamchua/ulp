package com.ulp.features.flashcards.service;

import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.entity.Flashcard;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Flip-mode study fetch (ULP-5.x): all cards of a deck the caller may study. */
@Service
public class FlashcardStudyService {

    private final FlashcardRepository cardRepository;
    private final DeckAccessResolver accessResolver;

    public FlashcardStudyService(FlashcardRepository cardRepository,
                                 DeckAccessResolver accessResolver) {
        this.cardRepository = cardRepository;
        this.accessResolver = accessResolver;
    }

    /**
     * Returns the deck's cards in study order for flip mode. Access is gated by
     * {@link DeckAccessResolver#requireViewable} (OWNER or SHARED_MEMBER); an
     * outsider gets 404.
     */
    @Transactional(readOnly = true)
    public List<CardView> getStudyCards(Long deckId, Long userId) {
        accessResolver.requireViewable(deckId, userId);
        List<CardView> cards = new ArrayList<>();
        for (Flashcard c : cardRepository.findByDeckIdOrderBySortOrderAsc(deckId)) {
            cards.add(new CardView(c.getId(), c.getFrontText(), c.getBackText()));
        }
        return cards;
    }
}
