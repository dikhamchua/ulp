package com.ulp.features.flashcards.service;

import com.ulp.features.classes.service.invites.InviteTokenGenerator;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardDeckRepository;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the deck's public-link state: turning the link on and off, minting a
 * replacement token, and resolving a token for anonymous viewing.
 *
 * <p>Split from {@link DeckShareService} (which owns class targets) because the
 * two live in different places — class targets in {@code flashcard_deck_classes},
 * link state on the deck row itself — and have different authorization shapes:
 * every mutation here is owner-only, while {@link #resolvePublic} has no caller
 * identity at all.
 *
 * <p>Token generation reuses {@link InviteTokenGenerator} rather than adding a
 * second generator: its LINK shape is 32 base64url chars from 24 random bytes
 * (192 bits), far beyond guessing.
 */
@Service
public class DeckPublicLinkService {

    private final FlashcardDeckRepository deckRepository;
    private final DeckAccessResolver accessResolver;
    private final InviteTokenGenerator tokenGenerator;

    public DeckPublicLinkService(FlashcardDeckRepository deckRepository,
                                 DeckAccessResolver accessResolver,
                                 InviteTokenGenerator tokenGenerator) {
        this.deckRepository = deckRepository;
        this.accessResolver = accessResolver;
        this.tokenGenerator = tokenGenerator;
    }

    /**
     * Turns the link on, minting a token only when the deck has none; owner-only.
     *
     * @return the deck's token, unchanged when one already existed
     * @throws AccessDeniedException if the caller is not the deck owner
     */
    @Transactional
    public String enable(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.enablePublicLink(tokenGenerator.generateLink());
        deckRepository.save(deck);
        return deck.getShareToken();
    }

    /**
     * Turns the link off; owner-only. The token survives, so re-enabling revives
     * URLs already handed out.
     *
     * @throws AccessDeniedException if the caller is not the deck owner
     */
    @Transactional
    public void disable(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.disablePublicLink();
        deckRepository.save(deck);
    }

    /**
     * Mints a fresh token, permanently killing every URL already shared, and
     * leaves the link switched on; owner-only.
     *
     * @return the replacement token
     * @throws AccessDeniedException if the caller is not the deck owner
     */
    @Transactional
    public String regenerate(Long deckId, Long ownerId) {
        FlashcardDeck deck = accessResolver.requireOwner(deckId, ownerId);
        deck.regenerateToken(tokenGenerator.generateLink());
        // Regenerating is how an owner replaces a leaked link, so the new one
        // must work immediately rather than needing a separate enable.
        deck.enablePublicLink(deck.getShareToken());
        deckRepository.save(deck);
        return deck.getShareToken();
    }

    /**
     * Resolves a token to a deck for anonymous viewing. Does not go through
     * {@link DeckAccessResolver} — there is no caller identity — so it guards
     * itself.
     *
     * @throws EntityNotFoundException when the token is unknown, the deck is
     *         soft-deleted, or the link is switched off — one identical error
     *         for all three so deck existence is never leaked
     */
    @Transactional(readOnly = true)
    public FlashcardDeck resolvePublic(String token) {
        if (token == null || token.isBlank()) {
            throw new EntityNotFoundException(DeckAccessResolver.NF_MSG);
        }
        FlashcardDeck deck = deckRepository.findByShareToken(token)
                .orElseThrow(() -> new EntityNotFoundException(DeckAccessResolver.NF_MSG));
        // Persistence-context cache can hand back a deck soft-deleted in this
        // same tx even though @SQLRestriction filters it at the DB level.
        if (deck.isDeleted() || !deck.isPublicLink()) {
            throw new EntityNotFoundException(DeckAccessResolver.NF_MSG);
        }
        return deck;
    }
}
