package com.ulp.features.ai.log;

import com.ulp.entities.AiProvider;
import com.ulp.entities.AiRequestLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes one {@code ai_request_logs} row per AI provider attempt.
 *
 * <p><b>Never breaks the caller.</b> Every write is wrapped in a try/catch that only
 * warns — an observability feature must not turn a working AI response into an error
 * page. This mirrors the swallow-and-report style {@code AiProviderService.test()}
 * already uses.
 *
 * <p>The actual insert runs in its own short transaction, delegated to
 * {@link AiRequestLogWriter}; see that class for why the transaction boundary and the
 * try/catch have to live in two different beans.
 */
@Component
public class AiRequestLogger {

    private static final Logger log = LoggerFactory.getLogger(AiRequestLogger.class);

    /** Marks a call made by the admin "test connection" button. */
    public static final String SOURCE_TEST_CONNECTION = "TEST_CONNECTION";

    /** Marks a call made by the ordinary chat path through the fallback chain. */
    public static final String SOURCE_CHAT = "CHAT";

    /** Marks a call made by the lecturer AI question generator (ULP-12.2). */
    public static final String SOURCE_QUESTION_GEN = "QUESTION_GEN";

    /** Marks a call made by the AI flashcard generator in the deck editor (ULP-12.4). */
    public static final String SOURCE_FLASHCARD_GEN = "FLASHCARD_GEN";

    private final AiRequestLogWriter writer;

    AiRequestLogger(AiRequestLogWriter writer) {
        this.writer = writer;
    }

    /**
     * Records a provider attempt that produced a usable completion.
     *
     * @param provider   the provider that answered
     * @param usage      token counts parsed from the response; any field may be {@code null}
     * @param durationMs wall-clock duration of the HTTP call
     * @param source     what triggered the call
     * @param userId     the acting user, or {@code null} for a system-initiated call
     */
    public void logSuccess(AiProvider provider, TokenUsage usage, long durationMs,
                           String source, Long userId) {
        AiRequestLog row = base(provider, AiRequestLog.STATUS_SUCCESS, source, userId, durationMs);
        if (usage != null) {
            row.setPromptTokens(usage.promptTokens());
            row.setCompletionTokens(usage.completionTokens());
            row.setTotalTokens(usage.totalTokens());
        }
        persist(row);
    }

    /**
     * Records a provider attempt that failed, whether transiently or permanently.
     *
     * @param provider   the provider that was contacted
     * @param error      failure detail; truncated to fit the column
     * @param durationMs wall-clock duration up to the failure
     * @param source     what triggered the call
     * @param userId     the acting user, or {@code null} for a system-initiated call
     */
    public void logFailure(AiProvider provider, String error, long durationMs,
                           String source, Long userId) {
        AiRequestLog row = base(provider, AiRequestLog.STATUS_FAILED, source, userId, durationMs);
        row.setErrorMessage(truncate(error));
        persist(row);
    }

    // ─────────────────────────────────────────────────────────────────

    /** Builds the row fields every attempt shares, regardless of outcome. */
    private static AiRequestLog base(AiProvider provider, String status, String source,
                                     Long userId, long durationMs) {
        AiRequestLog row = new AiRequestLog(provider.getName(), provider.getModel(), status, source);
        row.setProviderId(provider.getId());
        row.setCreatedBy(userId);
        row.setDurationMs(clampDuration(durationMs));
        return row;
    }

    /** Delegates the commit and downgrades any persistence failure to a warning. */
    private void persist(AiRequestLog row) {
        try {
            writer.write(row);
        } catch (RuntimeException e) {
            log.warn("Failed to write AI request log for provider '{}': {}",
                    row.getProviderName(), e.toString());
        }
    }

    /** Keeps the error text inside the column width so the insert cannot fail on length. */
    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        return error.length() <= AiRequestLog.MAX_ERROR_LENGTH
                ? error
                : error.substring(0, AiRequestLog.MAX_ERROR_LENGTH - 1) + "…";
    }

    /** Guards the INT column against a pathological clock jump producing an overflow. */
    private static Integer clampDuration(long durationMs) {
        if (durationMs < 0) {
            return null;
        }
        return durationMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) durationMs;
    }

    /**
     * Token counts lifted from an OpenAI-compatible {@code usage} object.
     *
     * <p>Every field is nullable: a provider that omits {@code usage}, or omits one of
     * its members, leaves the corresponding column {@code NULL}. Zero is never
     * substituted, because zero would assert the call was free.
     */
    public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
