package com.ulp.classes.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for pending {@link ImportSession} objects.
 *
 * <p>Sessions are keyed by a server-generated UUID and live for at most
 * {@link ImportSession#TTL_MINUTES} minutes. A {@link Scheduled} sweeper runs
 * every minute to evict expired entries. Reading or writing the same key is
 * thread-safe via {@link ConcurrentHashMap}.
 *
 * <p>Ownership is enforced on every {@link #get} call: a session belongs to
 * the lecturer who uploaded it, and is also pinned to the {@code classId}
 * captured at upload time. This means a malicious user cannot replay a peer's
 * session against a different class even if they somehow obtain the UUID.
 *
 * <p><b>V1 limitations (known):</b>
 * <ul>
 *   <li><b>Single-instance only.</b> The map lives in the JVM heap, so a
 *       load-balanced deployment where preview hits instance A and confirm
 *       hits instance B will fail with "session not found".</li>
 *   <li><b>Lost on restart.</b> All pending sessions are wiped when the app
 *       restarts. The lecturer must re-upload.</li>
 *   <li><b>TTL fixed at 10 minutes.</b> Not configurable per session.</li>
 * </ul>
 * Acceptable for the current scope (single-node demo deployment). When the
 * app moves to multi-instance, swap to a shared store (DB table or Redis) —
 * the {@code save/get/delete} contract stays the same.
 */
@Service
public class ImportSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ImportSessionStore.class);

    private final ConcurrentHashMap<UUID, ImportSession> sessions = new ConcurrentHashMap<>();

    /**
     * Persists the session and returns the UUID assigned to it. Callers are
     * expected to construct the session with {@code UUID.randomUUID()} before
     * invoking this method so that the UUID can be exposed directly to the
     * frontend without re-loading the session afterwards.
     */
    public UUID save(ImportSession session) {
        sessions.put(session.getId(), session);
        return session.getId();
    }

    /**
     * Returns the session if (a) it exists, (b) has not expired, and (c) its
     * owner matches the supplied lecturer. Expired sessions are evicted on read.
     */
    public Optional<ImportSession> get(UUID id, Long lecturerId) {
        if (id == null) return Optional.empty();
        ImportSession session = sessions.get(id);
        if (session == null) return Optional.empty();
        if (session.isExpired(Instant.now())) {
            sessions.remove(id);
            return Optional.empty();
        }
        if (!session.getLecturerId().equals(lecturerId)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** Explicit removal — called after a successful confirm so the UUID cannot be replayed. */
    public void delete(UUID id) {
        if (id != null) sessions.remove(id);
    }

    /** Returns the current number of cached sessions (test/observability helper). */
    int size() {
        return sessions.size();
    }

    /**
     * Background cleanup that drops sessions older than {@link ImportSession#TTL_MINUTES}.
     * The first run is delayed by 60 s to avoid sweeping during application
     * startup, and subsequent runs fire every 60 s thereafter.
     */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void evictExpired() {
        Instant now = Instant.now();
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        int after = sessions.size();
        if (before != after) {
            log.debug("Evicted {} expired import sessions (remaining={})", before - after, after);
        }
    }
}
