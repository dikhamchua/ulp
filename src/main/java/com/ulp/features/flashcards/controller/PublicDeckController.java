package com.ulp.features.flashcards.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.PublicDeckView;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import com.ulp.features.flashcards.service.DeckPublicLinkService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.ulp.common.IConstant.ATTR_CARDS_JSON;
import static com.ulp.common.IConstant.ATTR_DECK;
import static com.ulp.common.IConstant.PATH_PUBLIC_DECK;
import static com.ulp.common.IConstant.VIEW_PUBLIC_DECK;

/**
 * Renders a flashcard deck reached by its public link. Endpoint is
 * {@code permitAll} in SecurityConfig — visitors are anonymous and there is no
 * principal to authorize against.
 *
 * <p>Authorization is therefore entirely the token's job: only a deck whose
 * link is switched on, and which is not soft-deleted, resolves at all. The
 * 32-char base64url token carries 192 bits of entropy, so it cannot be guessed.
 *
 * <p>The view-model is deliberately narrow ({@link PublicDeckView}): title,
 * description and card count only. Owner identity, target classes and internal
 * ids never reach the model, and the page carries no edit, delete or share
 * control. Study progress is not recorded — anonymous visitors have no SM-2
 * state.
 */
@Controller
public class PublicDeckController {

    private final DeckPublicLinkService publicLinkService;
    private final FlashcardRepository cardRepository;
    private final ObjectMapper objectMapper;

    public PublicDeckController(DeckPublicLinkService publicLinkService,
                                FlashcardRepository cardRepository,
                                ObjectMapper objectMapper) {
        this.publicLinkService = publicLinkService;
        this.cardRepository = cardRepository;
        this.objectMapper = objectMapper;
    }

    /** Renders a deck reached by public link; anonymous, read-only. */
    @GetMapping(PATH_PUBLIC_DECK + "/{token}")
    public String view(@PathVariable String token, Model model) {
        FlashcardDeck deck;
        try {
            deck = publicLinkService.resolvePublic(token);
        } catch (EntityNotFoundException ex) {
            // One 404 for unknown / deleted / switched-off — never leak which.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<CardView> cards = cardRepository.findByDeckIdOrderBySortOrderAsc(deck.getId())
                .stream()
                .map(c -> new CardView(c.getId(), c.getFrontText(), c.getBackText()))
                .toList();
        model.addAttribute(ATTR_DECK, new PublicDeckView(
                deck.getTitle(), deck.getDescription(), cards.size()));
        model.addAttribute(ATTR_CARDS_JSON, toJson(cards));
        return VIEW_PUBLIC_DECK;
    }

    /** Serializes the card list to a JSON string for the data attribute. */
    private String toJson(List<CardView> cards) {
        try {
            return objectMapper.writeValueAsString(cards);
        } catch (JsonProcessingException e) {
            // Defensive: an empty array keeps the client renderer safe.
            return "[]";
        }
    }
}
