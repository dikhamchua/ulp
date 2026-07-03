package com.ulp.features.flashcards.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ulp.features.flashcards.service.DeckService;
import com.ulp.features.flashcards.service.FlashcardStudyService;
import com.ulp.features.flashcards.service.SmartReviewService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static com.ulp.common.IConstant.ATTR_CARDS_JSON;
import static com.ulp.common.IConstant.ATTR_DECK;
import static com.ulp.common.IConstant.ATTR_DUE_COUNT;
import static com.ulp.common.IConstant.BASE_FLASHCARDS;
import static com.ulp.common.IConstant.VIEW_FLASHCARD_FLIP;
import static com.ulp.common.IConstant.VIEW_FLASHCARD_REVIEW;

/**
 * Study-mode pages for a deck: flip-through and SM-2 Smart Review. Cards are
 * serialized to JSON and passed via a {@code data-*} attribute so the client
 * renders user content with {@code textContent} (never {@code innerHTML}).
 */
@Controller
@RequestMapping(BASE_FLASHCARDS)
@PreAuthorize("isAuthenticated()")
public class FlashcardStudyController {

    private final FlashcardStudyService studyService;
    private final SmartReviewService smartReviewService;
    private final DeckService deckService;
    private final ObjectMapper objectMapper;

    public FlashcardStudyController(FlashcardStudyService studyService,
                                    SmartReviewService smartReviewService,
                                    DeckService deckService,
                                    ObjectMapper objectMapper) {
        this.studyService = studyService;
        this.smartReviewService = smartReviewService;
        this.deckService = deckService;
        this.objectMapper = objectMapper;
    }

    /** Flip study mode: all cards in order. */
    @GetMapping("/{id}/flip")
    public String flip(@PathVariable Long id,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        DeckDetailView deck = deckService.getDetail(id, user.getId());
        List<CardView> cards = studyService.getStudyCards(id, user.getId());
        model.addAttribute(ATTR_DECK, deck);
        model.addAttribute(ATTR_CARDS_JSON, toJson(cards));
        return VIEW_FLASHCARD_FLIP;
    }

    /** Smart Review mode: only cards due now for the caller. */
    @GetMapping("/{id}/review")
    public String review(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        DeckDetailView deck = deckService.getDetail(id, user.getId());
        List<CardView> due = smartReviewService.getDueCards(id, user.getId());
        model.addAttribute(ATTR_DECK, deck);
        model.addAttribute(ATTR_DUE_COUNT, due.size());
        model.addAttribute(ATTR_CARDS_JSON, toJson(due));
        return VIEW_FLASHCARD_REVIEW;
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
