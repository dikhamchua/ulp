package com.ulp;

import com.ulp.entities.User;
import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.client.AiClientException;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardDeckRepository;
import com.ulp.features.flashcards.repository.FlashcardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 8 integration test for the AI flashcard generator (ULP-12.4).
 *
 * <p>The load-bearing assertion is {@link #successful_generation_persists_nothing()}: the
 * endpoint's whole contract is that it returns rows for the editor to append and writes
 * nothing, so the row count of {@code flashcards} must be identical before and after a
 * generation that succeeded. Everything else here guards the path to that call — who may
 * ask, for which deck, and with what material.
 *
 * <p>{@link AiClient} is a {@link MockitoBean}: the provider fallback chain has its own
 * coverage, and stubbing it here keeps the test off the network and makes the "nothing was
 * persisted" claim about this feature's code rather than about a provider being down. It
 * also means no {@code ai_request_logs} row is committed, so no cleanup is needed — unlike
 * {@link Sprint8AiQuestionGeneratorIntegrationTest}, which lets the call reach the client.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint8AiFlashcardGeneratorIntegrationTest {

    private static final String OWNER = "student@ulp.edu.vn";
    /** Authenticated, but owns none of the fixture decks. */
    private static final String OTHER_USER = "lecturer@ulp.edu.vn";

    private static final String MATERIAL =
            "Hàng đợi (queue) hoạt động theo nguyên tắc FIFO. Ngăn xếp (stack) theo LIFO.";

    /** A well-formed reply, so a successful generation reaches the persistence assertion. */
    private static final String AI_REPLY = """
            {"cards":[{"front":"Hàng đợi (queue)","back":"Cấu trúc dữ liệu FIFO"},
                      {"front":"Ngăn xếp (stack)","back":"Cấu trúc dữ liệu LIFO"}]}""";

    /** An id no deck can hold, standing in for a deck that was never created. */
    private static final long UNKNOWN_DECK_ID = 99_999_999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private FlashcardDeckRepository deckRepository;
    @Autowired private FlashcardRepository flashcardRepository;

    @MockitoBean private AiClient aiClient;

    private Long deckId;
    private Long deletedDeckId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow();
        deckId = deckRepository.saveAndFlush(
                new FlashcardDeck(owner.getId(), "Bộ thẻ AI JUnit", "mô tả")).getId();

        FlashcardDeck deleted = deckRepository.saveAndFlush(
                new FlashcardDeck(owner.getId(), "Bộ thẻ đã xoá", null));
        deleted.markDeleted();
        deletedDeckId = deckRepository.saveAndFlush(deleted).getId();

        Mockito.when(aiClient.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyString()))
                .thenReturn(AI_REPLY);
    }

    // ───────── The contract: generation never persists ───────────────

    /**
     * A generation that succeeded must leave {@code flashcards} untouched.
     *
     * <p>Asserted on the whole-table count, not on the deck's own count: a row written to
     * the wrong deck would still be unreviewed model output reaching the database, and a
     * per-deck count would not see it.
     */
    @Test
    @WithUserDetails(OWNER)
    void successful_generation_persists_nothing() throws Exception {
        long before = flashcardRepository.count();

        mockMvc.perform(generateRequest(deckId).param("text", MATERIAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.count").value(2))
                .andExpect(jsonPath("$.data.cards[0].front").value("Hàng đợi (queue)"))
                .andExpect(jsonPath("$.data.cards[0].back").value("Cấu trúc dữ liệu FIFO"));

        assertEquals(before, flashcardRepository.count(),
                "AI generation must return rows for review, never write cards");
    }

    // ───────── Access control ────────────────────────────────────────

    @Test
    @WithUserDetails(OTHER_USER)
    void non_owner_cannot_generate_for_someone_elses_deck() throws Exception {
        mockMvc.perform(generateRequest(deckId).param("text", MATERIAL))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails(OWNER)
    void generating_for_an_unknown_deck_is_not_found() throws Exception {
        mockMvc.perform(generateRequest(UNKNOWN_DECK_ID).param("text", MATERIAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
    }

    /** A soft-deleted deck must read as absent, not as an empty deck still accepting cards. */
    @Test
    @WithUserDetails(OWNER)
    void generating_for_a_deleted_deck_is_not_found() throws Exception {
        mockMvc.perform(generateRequest(deletedDeckId).param("text", MATERIAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.ok").value(false));
    }

    /**
     * The endpoint is CSRF-protected, so the browser module must send the token.
     *
     * <p>Every other test injects one via {@code .with(csrf())}, which would hide a front
     * end that forgot the header; this one omits it so the guard failing fails the build
     * rather than the user.
     */
    @Test
    @WithUserDetails(OWNER)
    void generate_without_a_csrf_token_is_forbidden() throws Exception {
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/ai-generate")
                        .param("count", "5")
                        .param("text", MATERIAL))
                .andExpect(status().isForbidden());
    }

    // ───────── Input guards ─────────────────────────────────────────

    @Test
    @WithUserDetails(OWNER)
    void generate_without_a_file_or_text_is_rejected_in_vietnamese() throws Exception {
        mockMvc.perform(generateRequest(deckId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Vui lòng tải lên tài liệu hoặc dán nội dung để sinh thẻ"));
    }

    @Test
    @WithUserDetails(OWNER)
    void generate_rejects_a_file_that_is_neither_pdf_nor_docx() throws Exception {
        // Named .pdf on purpose: the format check reads magic bytes, not the file name.
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf",
                "day khong phai la PDF".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(generateRequest(deckId).file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    // ───────── Failure surfaces ─────────────────────────────────────

    /** An unusable model reply is a retryable 400 with a message, not a 500. */
    @Test
    @WithUserDetails(OWNER)
    void an_unparseable_reply_is_reported_as_a_bad_request() throws Exception {
        Mockito.when(aiClient.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyString()))
                .thenReturn("Xin lỗi, tôi không thể sinh thẻ.");

        mockMvc.perform(generateRequest(deckId).param("text", MATERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    /** A provider outage surfaces as a retryable 400 carrying the client's own message. */
    @Test
    @WithUserDetails(OWNER)
    void a_provider_outage_is_reported_as_a_bad_request() throws Exception {
        Mockito.when(aiClient.chat(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyInt(), ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyString()))
                .thenThrow(new AiClientException("Chưa cấu hình AI provider nào đang bật."));

        mockMvc.perform(generateRequest(deckId).param("text", MATERIAL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Chưa cấu hình AI provider")));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private MockMultipartHttpServletRequestBuilder generateRequest(Long targetDeckId) {
        var builder = multipart("/api/flashcards/" + targetDeckId + "/ai-generate");
        builder.with(csrf());
        builder.param("count", "5");
        return builder;
    }
}
