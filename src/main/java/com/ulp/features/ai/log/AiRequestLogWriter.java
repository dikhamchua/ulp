package com.ulp.features.ai.log;

import com.ulp.entities.AiRequestLog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a single {@code ai_request_logs} row in its own short transaction.
 *
 * <p>Split out of {@link AiRequestLogger} on purpose. The swallow-the-failure
 * try/catch has to sit <i>outside</i> the transaction boundary: catching a
 * persistence exception inside a {@code REQUIRES_NEW} method still leaves that
 * transaction marked rollback-only, and the commit at method exit then throws
 * {@code UnexpectedRollbackException} straight into the AI call site — the very
 * failure the catch was meant to prevent. Separating the two means Spring's proxy
 * really does wrap only the insert, and the caller can catch whatever escapes.
 *
 * <p>{@link Propagation#REQUIRES_NEW} rather than the default: the AI call sites are
 * intentionally non-transactional, and a caller that later rolls back must not erase
 * the record of the attempt that caused it.
 *
 * <p>The insert takes no lock on {@code ai_providers}: {@code provider_id} deliberately
 * carries no foreign key (see {@code V38__ai_request_logs.sql}), so there is no parent
 * row to wait on and no lock-wait timeout to bound here.
 */
@Component
class AiRequestLogWriter {

    private final AiRequestLogRepository repository;

    AiRequestLogWriter(AiRequestLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts one log row and commits immediately.
     *
     * @param row the fully populated row to persist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AiRequestLog row) {
        repository.save(row);
    }
}
