package com.ulp.features.ai.questiongen;

import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftQuestion;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.Preview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One in-memory preview of AI-generated questions, awaiting the lecturer's selection.
 *
 * <p>Drafts are held in the JVM rather than a table, mirroring
 * {@code QuestionBankImportSession}: nothing here is worth persisting once the lecturer
 * walks away, and a short-lived preview keeps unreviewed model output out of the schema.
 *
 * <p>The session records both the actor and the exam it belongs to, so a confirm call
 * cannot replay another lecturer's drafts, nor land them in a different exam.
 */
public final class AiQuestionDraftSession {

    /** How long a preview stays usable before the lecturer must generate again. */
    public static final long TTL_MINUTES = 10L;

    private final UUID id;
    private final Long actorId;
    private final Long testId;
    private final Instant createdAt;
    private final List<DraftQuestion> questions;

    /**
     * Creates a preview session.
     *
     * @param id        the identifier the browser sends back on confirm
     * @param actorId   the lecturer who generated these drafts
     * @param testId    the exam the drafts are destined for
     * @param createdAt the moment generation completed; drives expiry
     * @param questions the validated drafts, in model order
     */
    public AiQuestionDraftSession(UUID id, Long actorId, Long testId, Instant createdAt,
                                  List<DraftQuestion> questions) {
        this.id = id;
        this.actorId = actorId;
        this.testId = testId;
        this.createdAt = createdAt;
        this.questions = List.copyOf(questions);
    }

    public UUID getId() {
        return id;
    }

    public Long getActorId() {
        return actorId;
    }

    public Long getTestId() {
        return testId;
    }

    public List<DraftQuestion> getQuestions() {
        return questions;
    }

    /** The browser-facing view of this session. */
    public Preview toPreview() {
        return new Preview(id, questions);
    }

    /**
     * Whether the session has outlived its TTL.
     *
     * @param now the current instant
     * @return {@code true} when the preview may no longer be confirmed
     */
    public boolean isExpired(Instant now) {
        return createdAt.plusSeconds(TTL_MINUTES * 60).isBefore(now);
    }
}
