package com.ulp.features.ai.flashcardgen;

import java.util.List;

/**
 * Transport records for the AI flashcard generator (ULP-12.4).
 *
 * <p>Grouped in one holder like {@code AiQuestionGenDtos}: these shapes only ever travel
 * together, between the flashcard API controller, the generation service and the deck
 * editor panel.
 *
 * <p>There is deliberately no draft-session record here. Generated cards are appended to
 * the editor DOM and committed by the existing {@code replaceCards} save, so nothing needs
 * to be held server-side between generation and save.
 */
public final class AiFlashcardGenDtos {

    private AiFlashcardGenDtos() {
        // DTO holder
    }

    /**
     * Generation parameters posted alongside the uploaded file or pasted text.
     *
     * @param count    how many cards the user asked for; clamped by the prompt builder
     * @param language the requested output language, or {@code null}/blank to follow the
     *                 language of the source material
     */
    public record GenerateRequest(int count, String language) {
    }

    /**
     * One generated card, not yet persisted.
     *
     * <p>Field names match {@code ImportedCardRow} so the browser can append rows from an
     * Excel import and from a generation through the same code path.
     *
     * @param front the term or short question shown first
     * @param back  the definition or answer revealed on flip
     */
    public record GeneratedCardRow(String front, String back) {
    }

    /**
     * The payload handed back to the browser after a successful generation.
     *
     * @param cards the generated cards, in the order the model produced them
     * @param count how many cards {@code cards} carries, mirroring {@code ImportResult}
     */
    public record GenerateResult(List<GeneratedCardRow> cards, int count) {
    }
}
