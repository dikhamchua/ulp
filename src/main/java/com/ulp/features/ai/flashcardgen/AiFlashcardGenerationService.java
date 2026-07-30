package com.ulp.features.ai.flashcardgen;

import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GenerateRequest;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GeneratedCardRow;
import com.ulp.features.ai.log.AiRequestLogger;
import com.ulp.features.ai.questiongen.DocumentTextExtractor;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Generates flashcard rows from user-supplied material for an owned deck.
 *
 * <p>Single-step by design, mirroring the Excel import flow rather than the two-step
 * question generator: the rows are handed straight back to the deck editor, which appends
 * them as unsaved fields. Nothing reaches {@code flashcards} until the user presses save,
 * so no draft session has to be held server-side and unreviewed model output is never
 * persisted.
 *
 * <p>{@link DocumentTextExtractor} is reused as-is from the question generator — the
 * PDF/DOCX handling and the size caps are identical, so a second copy would only be a
 * second thing to keep in step.
 */
@Service
public class AiFlashcardGenerationService {

    private final DeckAccessResolver accessResolver;
    private final DocumentTextExtractor textExtractor;
    private final AiFlashcardPromptBuilder promptBuilder;
    private final AiFlashcardResponseParser responseParser;
    private final AiClient aiClient;

    public AiFlashcardGenerationService(DeckAccessResolver accessResolver,
                                        DocumentTextExtractor textExtractor,
                                        AiFlashcardPromptBuilder promptBuilder,
                                        AiFlashcardResponseParser responseParser,
                                        AiClient aiClient) {
        this.accessResolver = accessResolver;
        this.textExtractor = textExtractor;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.aiClient = aiClient;
    }

    /**
     * Generates card rows from an uploaded file or pasted text.
     *
     * <p>Runs outside a transaction, and is deliberately not annotated
     * {@code @Transactional}: the provider call can take tens of seconds and nothing is
     * persisted here, so holding a database transaction open across it would pin a
     * connection for no benefit.
     *
     * <p>Ownership is checked before the material is read, so a caller who does not own the
     * deck never spends provider tokens.
     *
     * @param userId  the acting user
     * @param deckId  the deck the rows are destined for
     * @param file    an uploaded PDF/DOCX, or {@code null} when text was pasted
     * @param text    pasted material, used when no file was supplied
     * @param request the generation parameters
     * @return the generated rows and how many there are
     * @throws org.springframework.security.access.AccessDeniedException when the caller does
     *         not own the deck
     * @throws jakarta.persistence.EntityNotFoundException when the deck does not exist or
     *         has been deleted
     * @throws IllegalArgumentException when the material cannot be read or the model reply
     *         is unusable
     * @throws com.ulp.features.ai.client.AiClientException when no provider answered
     */
    public AiFlashcardGenDtos.GenerateResult generate(Long userId, Long deckId, MultipartFile file,
                                                      String text, GenerateRequest request) {
        accessResolver.requireOwner(deckId, userId);

        String material = (file != null && !file.isEmpty())
                ? textExtractor.extract(file)
                : textExtractor.normalizePastedText(text);

        String reply = aiClient.chat(
                promptBuilder.systemPrompt(),
                promptBuilder.userMessage(request, material),
                AiFlashcardPromptBuilder.maxTokensFor(request.count()),
                userId,
                AiRequestLogger.SOURCE_FLASHCARD_GEN);

        List<GeneratedCardRow> rows = responseParser.parse(reply);
        return new AiFlashcardGenDtos.GenerateResult(rows, rows.size());
    }
}
