package com.ulp.features.flashcards.controller;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.service.CardService;
import com.ulp.features.flashcards.service.DeckPublicLinkService;
import com.ulp.features.flashcards.service.DeckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@link PublicDeckController}: the anonymous public-link
 * page must open without a session, must answer 404 identically for every
 * failure mode, and must not leak owner or class information into the model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicDeckControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private DeckPublicLinkService publicLinkService;
    @Autowired private UserRepository userRepository;

    private Long deckId;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        ownerId = owner.getId();
        deckId = deckService.createDeck(ownerId, new DeckForm("Bộ thẻ công khai", "Mô tả"));
        cardService.replaceCards(deckId, ownerId, List.of(
                new CardItem(null, "mặt trước", "mặt sau")));
    }

    @Test
    void anonymousCanOpenPublicDeck() throws Exception {
        String token = publicLinkService.enable(deckId, ownerId);

        mockMvc.perform(get("/s/" + token).with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(view().name("flashcards/public-deck"))
                .andExpect(model().attributeExists("deck", "cardsJson"));
    }

    @Test
    void anonymousGets404ForDisabledLink() throws Exception {
        String token = publicLinkService.enable(deckId, ownerId);
        publicLinkService.disable(deckId, ownerId);

        mockMvc.perform(get("/s/" + token).with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousGets404ForUnknownToken() throws Exception {
        mockMvc.perform(get("/s/khong-ton-tai-token").with(anonymous()))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicPageDoesNotExposeOwnerOrClasses() throws Exception {
        String token = publicLinkService.enable(deckId, ownerId);

        String html = mockMvc.perform(get("/s/" + token).with(anonymous()))
                .andExpect(status().isOk())
                // Owner identity and class targets have no attribute to hide in.
                .andExpect(model().attributeDoesNotExist("ownerId", "targetClasses",
                        "shareClasses", "shareToken", "memberCount"))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();

        // The rendered page must not carry the owner's name, email, or deck id.
        org.assertj.core.api.Assertions.assertThat(html)
                .doesNotContain("student@ulp.edu.vn")
                .doesNotContain("/my/flashcards/" + deckId);
    }
}
