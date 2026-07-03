package com.ulp.features.flashcards.controller;

import com.ulp.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckEditorView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.dto.FlashcardDtos.StudentDeckList;
import com.ulp.features.flashcards.service.CardService;
import com.ulp.features.flashcards.service.DeckService;
import com.ulp.features.flashcards.service.SmartReviewService;
import com.ulp.security.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;

/**
 * Student-facing controller for personal flashcard decks under
 * {@code /my/flashcards}. Any authenticated user may manage their own decks.
 *
 * <p>Authorization for individual decks is enforced at the service layer via
 * {@code DeckAccessResolver}: inaccessible decks raise 404, owner-only
 * mutations by a non-owner raise 403 (both mapped by {@code GlobalExceptionHandler}).
 */
@Controller
@RequestMapping(BASE_FLASHCARDS)
@PreAuthorize("isAuthenticated()")
public class StudentFlashcardController {

    private final DeckService deckService;
    private final CardService cardService;
    private final SmartReviewService smartReviewService;

    public StudentFlashcardController(DeckService deckService,
                                      CardService cardService,
                                      SmartReviewService smartReviewService) {
        this.deckService = deckService;
        this.cardService = cardService;
        this.smartReviewService = smartReviewService;
    }

    /**
     * Lists the caller's own decks (SSR numbered pager) + decks shared to their
     * classes (unpaginated). The pager fragment computes its own button window
     * from {@code ownDecksPage} via {@link com.ulp.common.PageWindow}.
     */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        StudentDeckList decks = deckService.listForStudent(user.getId(), page);
        model.addAttribute(ATTR_DECKS_OWN_PAGE, decks.ownDecks());
        model.addAttribute(ATTR_DECKS_SHARED, decks.sharedDecks());
        return VIEW_FLASHCARD_LIST;
    }

    /** Renders the new-deck form (metadata only). */
    @GetMapping("/new")
    public String newForm(Model model) {
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, DeckForm.empty());
        }
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        return VIEW_FLASHCARD_FORM;
    }

    /** Creates a PRIVATE deck then redirects to its card editor. */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") DeckForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        if (result.hasErrors()) {
            model.addAttribute(ATTR_MODE, MODE_CREATE);
            return VIEW_FLASHCARD_FORM;
        }
        Long deckId = deckService.createDeck(user.getId(), form);
        return "redirect:" + deckUrl(deckId) + "/edit";
    }

    /** Deck launcher page (owner or shared member): metadata + study launchers. */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model) {
        DeckDetailView deck = deckService.getDetail(id, user.getId());
        model.addAttribute(ATTR_DECK, deck);
        model.addAttribute(ATTR_SHARE_CLASSES, deck.shareClasses());
        model.addAttribute(ATTR_DUE_COUNT, smartReviewService.countDue(id, user.getId()));
        return VIEW_FLASHCARD_DETAIL;
    }

    /** Card editor page (owner-only): metadata + Quizlet-style card rows. */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model) {
        DeckEditorView editor = cardService.getEditorView(id, user.getId());
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new DeckForm(editor.title(), editor.description()));
        }
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_DECK, editor);
        model.addAttribute(ATTR_CARDS, editor.cards());
        return VIEW_FLASHCARD_FORM;
    }

    /** Saves deck metadata (cards are saved separately via AJAX before submit). */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") DeckForm form,
                         BindingResult result,
                         @AuthenticationPrincipal UlpUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        if (result.hasErrors()) {
            DeckEditorView editor = cardService.getEditorView(id, user.getId());
            model.addAttribute(ATTR_MODE, MODE_EDIT);
            model.addAttribute(ATTR_DECK, editor);
            model.addAttribute(ATTR_CARDS, editor.cards());
            return VIEW_FLASHCARD_FORM;
        }
        deckService.updateMetadata(id, user.getId(), form);
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_UPDATED);
        return "redirect:" + deckUrl(id);
    }

    /** Soft-deletes a deck; owner-only. */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        deckService.softDelete(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_DELETED);
        return "redirect:" + BASE_FLASHCARDS;
    }

    /** Shares a deck to one of the owner's classes; owner-only. */
    @PostMapping("/{id}/share")
    public String share(@PathVariable Long id,
                        @RequestParam("classId") Long classId,
                        @AuthenticationPrincipal UlpUserDetails user,
                        RedirectAttributes ra) {
        deckService.share(id, user.getId(), classId);
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_SHARED);
        return "redirect:" + deckUrl(id);
    }

    /** Reverts a deck to PRIVATE; owner-only. */
    @PostMapping("/{id}/unshare")
    public String unshare(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        deckService.unshare(id, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_DECK_UNSHARED);
        return "redirect:" + deckUrl(id);
    }

    /** Canonical URL for a single deck. Carries a path variable, so not a constant. */
    private static String deckUrl(Long id) {
        return BASE_FLASHCARDS + "/" + id;
    }
}
