package com.ulp.features.ai.questiongen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM-local store for pending AI question previews.
 *
 * <p>Mirrors {@code QuestionBankImportSessionStore}. A background sweep evicts expired
 * entries so an abandoned preview cannot pin generated text in memory indefinitely; the
 * per-read expiry check makes the sweep an optimisation rather than a correctness
 * requirement.
 */
@Service
public class AiQuestionDraftSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AiQuestionDraftSessionStore.class);

    private final ConcurrentHashMap<UUID, AiQuestionDraftSession> sessions = new ConcurrentHashMap<>();

    /**
     * Stores a preview session.
     *
     * @param session the session to keep until confirmed or expired
     * @return the session id the browser will send back
     */
    public UUID save(AiQuestionDraftSession session) {
        sessions.put(session.getId(), session);
        return session.getId();
    }

    /**
     * Looks up a live session belonging to the given actor.
     *
     * <p>An unknown id, an expired session and another lecturer's session are all reported
     * the same way — as absent — so the caller cannot use this endpoint to probe which
     * session ids exist.
     *
     * @param id      the session id from the confirm request
     * @param actorId the lecturer making the confirm call
     * @return the session, or empty when it is missing, expired or not this actor's
     */
    public Optional<AiQuestionDraftSession> get(UUID id, Long actorId) {
        if (id == null) {
            return Optional.empty();
        }
        AiQuestionDraftSession session = sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(Instant.now())) {
            sessions.remove(id);
            return Optional.empty();
        }
        if (!session.getActorId().equals(actorId)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Drops a session, typically once its drafts have been inserted.
     *
     * @param id the session to forget; {@code null} is ignored
     */
    public void delete(UUID id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    /** Sweeps expired sessions once a minute. */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void evictExpired() {
        Instant now = Instant.now();
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        int after = sessions.size();
        if (before != after) {
            log.debug("Evicted {} expired AI question draft sessions", before - after);
        }
    }
}
