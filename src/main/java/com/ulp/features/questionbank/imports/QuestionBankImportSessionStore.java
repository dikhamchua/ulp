package com.ulp.features.questionbank.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** JVM-local store for pending question bank import previews. */
@Service
public class QuestionBankImportSessionStore {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankImportSessionStore.class);

    private final ConcurrentHashMap<UUID, QuestionBankImportSession> sessions = new ConcurrentHashMap<>();

    public UUID save(QuestionBankImportSession session) {
        sessions.put(session.getId(), session);
        return session.getId();
    }

    public Optional<QuestionBankImportSession> get(UUID id, Long actorId) {
        if (id == null) {
            return Optional.empty();
        }
        QuestionBankImportSession session = sessions.get(id);
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

    public void delete(UUID id) {
        if (id != null) {
            sessions.remove(id);
        }
    }

    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void evictExpired() {
        Instant now = Instant.now();
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        int after = sessions.size();
        if (before != after) {
            log.debug("Evicted {} expired question bank import sessions", before - after);
        }
    }
}
