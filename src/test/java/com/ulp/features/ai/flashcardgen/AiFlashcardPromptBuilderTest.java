package com.ulp.features.ai.flashcardgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the static, input-shaping half of {@link AiFlashcardPromptBuilder}.
 *
 * <p><b>Why this class exists.</b> {@code maxTokensFor} decides how much room the provider
 * has to answer, and an under-budgeted ceiling is what produced the original defect: the
 * reply was cut off at {@code max_tokens}, came back as partial JSON, and surfaced to the
 * user as an AI failure ({@code ai_request_logs} id 802 spent exactly the old ceiling of
 * 2400 completion tokens for 20 cards). The budget had no test at all, so shrinking the
 * coefficient back would have been silent. The expected token counts below are therefore
 * written as literals rather than recomputed from the formula — restating the arithmetic
 * would pass against any coefficient and pin nothing.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: every method under test is static, so no
 * repository and no application context are involved.
 */
class AiFlashcardPromptBuilderTest {

    /** Default the generation form offers, and the count the reported bug was hit with. */
    private static final int DEFAULT_COUNT = 20;

    // ───────── maxTokensFor — the budget itself ──────────────────────

    /**
     * Locks the ceiling for the default request size.
     *
     * <p>The old, defective budget for this count was 2400 tokens; anything near that value
     * failing here is the regression this test exists to catch.
     */
    @Test
    @DisplayName("maxTokensFor budgets 8500 tokens for the default 20 cards")
    void budgetsForTheDefaultCount() {
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(DEFAULT_COUNT)).isEqualTo(8500);
    }

    /**
     * A second count proves the shape of the formula, not just one memorised number.
     *
     * <p>Two points pin both terms: the gap between them is the per-card rate, and what is
     * left over is the flat envelope allowance. A build that dropped the addend, or changed
     * the rate, cannot satisfy both assertions at once.
     */
    @Test
    @DisplayName("maxTokensFor scales per card on top of a flat envelope allowance")
    void scalesPerCardAboveAFlatAddend() {
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(5)).isEqualTo(2500);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(10)).isEqualTo(4500);

        // 5 extra cards buy 2000 extra tokens; the remainder is the constant envelope.
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(10)
                - AiFlashcardPromptBuilder.maxTokensFor(5)).isEqualTo(2000);
    }

    /**
     * The ceiling must never scale past the supported maximum.
     *
     * <p>{@code count} arrives from a form, so an absurd value has to clamp <em>before</em>
     * it multiplies — scaling first would let one request buy an unbounded, billable budget.
     */
    @Test
    @DisplayName("maxTokensFor clamps an oversized count before scaling")
    void clampsOversizedCountBeforeScaling() {
        int atMax = AiFlashcardPromptBuilder.maxTokensFor(AiFlashcardPromptBuilder.MAX_COUNT);

        assertThat(atMax).isEqualTo(20500);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(9_999)).isEqualTo(atMax);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(Integer.MAX_VALUE)).isEqualTo(atMax);
    }

    /**
     * A non-positive count must still buy a usable budget.
     *
     * <p>Without the lower clamp, zero or a negative count would yield a ceiling at or below
     * the flat addend, and the reply would be truncated before it could carry a single card.
     */
    @Test
    @DisplayName("maxTokensFor clamps an undersized count up to the minimum")
    void clampsUndersizedCountBeforeScaling() {
        int atMin = AiFlashcardPromptBuilder.maxTokensFor(AiFlashcardPromptBuilder.MIN_COUNT);

        assertThat(atMin).isEqualTo(900);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(0)).isEqualTo(atMin);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(-7)).isEqualTo(atMin);
        assertThat(AiFlashcardPromptBuilder.maxTokensFor(Integer.MIN_VALUE)).isEqualTo(atMin);
    }

    // ───────── clampCount ────────────────────────────────────────────

    @Test
    @DisplayName("clampCount raises a below-minimum count to MIN_COUNT")
    void clampsBelowMinimum() {
        assertThat(AiFlashcardPromptBuilder.clampCount(0))
                .isEqualTo(AiFlashcardPromptBuilder.MIN_COUNT);
        assertThat(AiFlashcardPromptBuilder.clampCount(-1))
                .isEqualTo(AiFlashcardPromptBuilder.MIN_COUNT);
    }

    @Test
    @DisplayName("clampCount leaves an in-range count untouched")
    void keepsInRangeCount() {
        assertThat(AiFlashcardPromptBuilder.clampCount(AiFlashcardPromptBuilder.MIN_COUNT))
                .isEqualTo(AiFlashcardPromptBuilder.MIN_COUNT);
        assertThat(AiFlashcardPromptBuilder.clampCount(DEFAULT_COUNT)).isEqualTo(DEFAULT_COUNT);
        assertThat(AiFlashcardPromptBuilder.clampCount(AiFlashcardPromptBuilder.MAX_COUNT))
                .isEqualTo(AiFlashcardPromptBuilder.MAX_COUNT);
    }

    @Test
    @DisplayName("clampCount lowers an above-maximum count to MAX_COUNT")
    void clampsAboveMaximum() {
        assertThat(AiFlashcardPromptBuilder.clampCount(AiFlashcardPromptBuilder.MAX_COUNT + 1))
                .isEqualTo(AiFlashcardPromptBuilder.MAX_COUNT);
        assertThat(AiFlashcardPromptBuilder.clampCount(1_000))
                .isEqualTo(AiFlashcardPromptBuilder.MAX_COUNT);
    }

    // ───────── normalizeLanguage ─────────────────────────────────────

    @Test
    @DisplayName("normalizeLanguage falls back to AUTO when no language was given")
    void fallsBackToAutoWhenAbsent() {
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage(null))
                .isEqualTo(AiFlashcardPromptBuilder.LANGUAGE_AUTO);
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage(""))
                .isEqualTo(AiFlashcardPromptBuilder.LANGUAGE_AUTO);
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage("   \t\n "))
                .isEqualTo(AiFlashcardPromptBuilder.LANGUAGE_AUTO);
    }

    /**
     * The value is pasted into the prompt verbatim, so an over-long one is rejected rather
     * than forwarded: at that length it is smuggled instructions, not a language name.
     * The literal below is sized past the cap, which is private to the builder.
     */
    @Test
    @DisplayName("normalizeLanguage falls back to AUTO for an over-long value")
    void fallsBackToAutoWhenOverlong() {
        String overlong = "x".repeat(41);

        assertThat(AiFlashcardPromptBuilder.normalizeLanguage(overlong))
                .isEqualTo(AiFlashcardPromptBuilder.LANGUAGE_AUTO);
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage("x".repeat(40)))
                .as("a value exactly at the cap is still accepted")
                .isEqualTo("x".repeat(40));
    }

    @Test
    @DisplayName("normalizeLanguage trims and lowercases an ordinary value")
    void trimsAndLowercasesOrdinaryValue() {
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage("  Tiếng Việt  "))
                .isEqualTo("tiếng việt");
        assertThat(AiFlashcardPromptBuilder.normalizeLanguage("ENGLISH")).isEqualTo("english");
    }
}
