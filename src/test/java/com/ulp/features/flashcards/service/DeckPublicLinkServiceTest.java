package com.ulp.features.flashcards.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.repository.FlashcardDeckRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link DeckPublicLinkService}: token lifecycle
 * (enable / disable / regenerate) and the three ways anonymous resolution
 * must fail identically.
 */
@SpringBootTest
@Transactional
class DeckPublicLinkServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private DeckPublicLinkService service;
    @Autowired private FlashcardDeckRepository deckRepository;
    @Autowired private UserRepository userRepository;

    private User owner;
    private User stranger;
    private Long deckId;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        stranger = userRepository.findByEmailIgnoreCase("sv01@ulp.edu.vn").orElseThrow();
        deckId = deckService.createDeck(owner.getId(), new DeckForm("Link công khai", null));
    }

    @Test
    void disableThenEnableKeepsTheSameToken() {
        String first = service.enable(deckId, owner.getId());
        service.disable(deckId, owner.getId());
        String second = service.enable(deckId, owner.getId());

        // Links already sent out must stay alive across an off/on toggle.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void regenerateChangesTheToken() {
        String first = service.enable(deckId, owner.getId());
        String second = service.regenerate(deckId, owner.getId());

        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> service.resolvePublic(first))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(service.resolvePublic(second).getId()).isEqualTo(deckId);
    }

    @Test
    void enableMakesTheDeckResolvableByToken() {
        String token = service.enable(deckId, owner.getId());
        assertThat(service.resolvePublic(token).getId()).isEqualTo(deckId);
    }

    @Test
    void resolvePublicRejectsDisabledLink() {
        String token = service.enable(deckId, owner.getId());
        service.disable(deckId, owner.getId());

        assertThatThrownBy(() -> service.resolvePublic(token))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void resolvePublicRejectsSoftDeletedDeck() {
        String token = service.enable(deckId, owner.getId());
        deckService.softDelete(deckId, owner.getId());

        assertThatThrownBy(() -> service.resolvePublic(token))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void resolvePublicRejectsUnknownToken() {
        assertThatThrownBy(() -> service.resolvePublic("khong-ton-tai-token"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void nonOwnerCannotEnableLink() {
        assertThatThrownBy(() -> service.enable(deckId, stranger.getId()))
                .isInstanceOf(AccessDeniedException.class);

        // Status alone would not prove the switch stayed off.
        assertThat(deckRepository.findById(deckId).orElseThrow().isPublicLink()).isFalse();
    }

    @Test
    void nonOwnerCannotDisableLink() {
        service.enable(deckId, owner.getId());

        assertThatThrownBy(() -> service.disable(deckId, stranger.getId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deckRepository.findById(deckId).orElseThrow().isPublicLink()).isTrue();
    }

    @Test
    void nonOwnerCannotRegenerateToken() {
        String token = service.enable(deckId, owner.getId());

        assertThatThrownBy(() -> service.regenerate(deckId, stranger.getId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(deckRepository.findById(deckId).orElseThrow().getShareToken())
                .isEqualTo(token);
    }
}
