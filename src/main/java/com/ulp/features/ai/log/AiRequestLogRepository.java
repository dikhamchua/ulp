package com.ulp.features.ai.log;

import com.ulp.entities.AiRequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the {@code ai_request_logs} table.
 *
 * <p>The admin screen always reads newest-first and always applies the same two
 * optional filters, so both the page query and the token aggregate take the same
 * nullable parameters. Passing {@code null} for a filter disables it, which keeps
 * the four filter combinations on one query instead of four method overloads.
 */
@Repository
public interface AiRequestLogRepository extends JpaRepository<AiRequestLog, Long> {

    /**
     * Returns one page of log rows, newest first, narrowed by the optional filters.
     *
     * @param providerName exact provider name snapshot, or {@code null} for all providers
     * @param status       {@code SUCCESS} / {@code FAILED}, or {@code null} for both
     * @param pageable     page number and size; sorting is fixed by the query
     * @return the matching page of log rows
     */
    @Query("SELECT l FROM AiRequestLog l "
            + "WHERE (:providerName IS NULL OR l.providerName = :providerName) "
            + "AND (:status IS NULL OR l.status = :status) "
            + "ORDER BY l.createdAt DESC, l.id DESC")
    Page<AiRequestLog> findFiltered(@Param("providerName") String providerName,
                                    @Param("status") String status,
                                    Pageable pageable);

    /**
     * Sums the token columns over the whole filtered set, not just the visible page.
     *
     * <p>Returns a single row of {@code [rowCount, promptTokens, completionTokens,
     * totalTokens]}. {@code SUM} yields {@code null} when no row carries a value, so
     * the caller must treat a null sum as "unknown" rather than zero.
     *
     * @param providerName exact provider name snapshot, or {@code null} for all providers
     * @param status       {@code SUCCESS} / {@code FAILED}, or {@code null} for both
     * @return a one-element list holding the aggregate row
     */
    @Query("SELECT COUNT(l), SUM(l.promptTokens), SUM(l.completionTokens), SUM(l.totalTokens) "
            + "FROM AiRequestLog l "
            + "WHERE (:providerName IS NULL OR l.providerName = :providerName) "
            + "AND (:status IS NULL OR l.status = :status)")
    List<Object[]> sumTokens(@Param("providerName") String providerName,
                             @Param("status") String status);

    /**
     * Lists the distinct provider names that appear in the log, for the filter dropdown.
     *
     * <p>Read from the log rather than from {@code ai_providers} so a provider an admin
     * has since deleted can still be filtered on.
     *
     * @return provider name snapshots, alphabetically ordered
     */
    @Query("SELECT DISTINCT l.providerName FROM AiRequestLog l ORDER BY l.providerName ASC")
    List<String> findDistinctProviderNames();

    /**
     * Returns one page of attempts for a single provider, newest first.
     *
     * <p>Used by the provider detail "Lịch sử hoạt động" tab. Filters on
     * {@code provider_id} (not the name snapshot) so renaming a provider does not
     * orphan its history while the row still exists.
     *
     * @param providerId the live provider identifier
     * @param pageable   page number and size; sorting is fixed by the query
     * @return matching log rows for that provider
     */
    @Query("SELECT l FROM AiRequestLog l "
            + "WHERE l.providerId = :providerId "
            + "ORDER BY l.createdAt DESC, l.id DESC")
    Page<AiRequestLog> findByProviderId(@Param("providerId") Long providerId, Pageable pageable);
}
