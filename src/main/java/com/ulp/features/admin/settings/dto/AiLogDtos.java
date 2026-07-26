package com.ulp.features.admin.settings.dto;

import java.time.LocalDateTime;

/**
 * DTOs for the {@code /admin/settings/ai/logs} screen.
 *
 * <p>{@link AiLogRow} is the read model for one provider attempt. Every token field is
 * an {@link Integer} rather than an {@code int} so the template can distinguish "the
 * provider did not report usage" ({@code null}, rendered as a dash) from a genuine zero.
 */
public class AiLogDtos {

    /** One row of the log table — one provider attempt. */
    public record AiLogRow(
            Long id,
            String providerName,
            String model,
            String status,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Integer durationMs,
            String errorMessage,
            String source,
            LocalDateTime createdAt
    ) {
        /** True when the attempt produced a usable completion — drives the badge colour. */
        public boolean succeeded() {
            return com.ulp.entities.AiRequestLog.STATUS_SUCCESS.equals(status);
        }
    }

    /**
     * Token totals across the whole filtered set, not merely the visible page.
     *
     * <p>The sums are {@code long} because a busy log can exceed the range of the
     * per-row {@code int} columns once aggregated.
     */
    public record AiLogTotals(
            long rowCount,
            long promptTokens,
            long completionTokens,
            long totalTokens
    ) {
        /** The zero totals used when the filter matches nothing. */
        public static AiLogTotals empty() {
            return new AiLogTotals(0L, 0L, 0L, 0L);
        }
    }

    /**
     * The active filter, echoed back so the form keeps its selection and the pagination
     * links carry it forward. A {@code null} member means "no filter on that column".
     */
    public record AiLogFilter(String provider, String status) {

        /** Builds a filter, normalising blank query parameters to {@code null}. */
        public static AiLogFilter of(String provider, String status) {
            return new AiLogFilter(blankToNull(provider), blankToNull(status));
        }

        private static String blankToNull(String value) {
            return (value == null || value.isBlank()) ? null : value.trim();
        }
    }
}
