package com.ulp.features.admin.settings.service;

import com.ulp.entities.AiRequestLog;
import com.ulp.features.admin.settings.dto.AiLogDtos.AiLogFilter;
import com.ulp.features.admin.settings.dto.AiLogDtos.AiLogRow;
import com.ulp.features.admin.settings.dto.AiLogDtos.AiLogTotals;
import com.ulp.features.ai.log.AiRequestLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side service for the AI request log screen.
 *
 * <p>Read-only by design: the log is append-only from the application's point of view.
 * Rows are written by {@code AiRequestLogger} on the call path and are never edited or
 * deleted here — retention is deliberately out of scope.
 */
@Service
@Transactional(readOnly = true)
public class AiLogQueryService {

    private final AiRequestLogRepository repository;

    public AiLogQueryService(AiRequestLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns one page of log rows matching the filter, newest first.
     *
     * @param filter   provider and status narrowing; either member may be {@code null}
     * @param pageable page number and size
     * @return the page of read models
     */
    public Page<AiLogRow> list(AiLogFilter filter, Pageable pageable) {
        return repository.findFiltered(filter.provider(), filter.status(), pageable)
                .map(AiLogQueryService::toRow);
    }

    /**
     * Sums the token columns over every row matching the filter, not just the page shown.
     *
     * <p>{@code SUM} returns {@code null} when no matching row carries a value, so each
     * sum is coalesced to zero here — for an aggregate across many rows, "nothing was
     * reported" and "the reported total is zero" are the same statement.
     *
     * @param filter provider and status narrowing; either member may be {@code null}
     * @return the totals, or {@link AiLogTotals#empty()} when nothing matched
     */
    public AiLogTotals totals(AiLogFilter filter) {
        List<Object[]> rows = repository.sumTokens(filter.provider(), filter.status());
        if (rows.isEmpty()) {
            return AiLogTotals.empty();
        }
        Object[] agg = rows.get(0);
        return new AiLogTotals(
                toLong(agg[0]), toLong(agg[1]), toLong(agg[2]), toLong(agg[3]));
    }

    /**
     * Lists the provider names that appear in the log, for the filter dropdown.
     *
     * @return distinct provider name snapshots, alphabetically ordered
     */
    public List<String> providerNames() {
        return repository.findDistinctProviderNames();
    }

    // ─────────────────────────────────────────────────────────────────

    /** Maps the entity onto the read model the template renders. */
    private static AiLogRow toRow(AiRequestLog log) {
        return new AiLogRow(
                log.getId(),
                log.getProviderName(),
                log.getModel(),
                log.getStatus(),
                log.getPromptTokens(),
                log.getCompletionTokens(),
                log.getTotalTokens(),
                log.getDurationMs(),
                log.getErrorMessage(),
                log.getSource(),
                log.getCreatedAt());
    }

    /** Coalesces a possibly-null aggregate cell to a primitive total. */
    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
