package com.ulp.features.ai.questiongen;

import java.util.List;
import java.util.UUID;

/**
 * Transport records for the lecturer AI question generator (ULP-12.2).
 *
 * <p>Grouped in one holder like {@code LecturerTestDtos}: these shapes only ever travel
 * together, between the controller, the generation service and the browser panel.
 */
public final class AiQuestionGenDtos {

    private AiQuestionGenDtos() {
        // DTO holder
    }

    /**
     * Generation parameters posted alongside the uploaded file or pasted text.
     *
     * @param count      how many questions the lecturer asked for
     * @param type       {@code MCQ} or {@code MR}
     * @param difficulty a free-form hint (easy/medium/hard) passed through to the model
     */
    public record GenerateRequest(int count, String type, String difficulty) {
    }

    /**
     * One answer option of a generated question, not yet persisted.
     *
     * @param content plain-text option body as returned by the model
     * @param correct whether the model marked this option as a correct answer
     */
    public record DraftOption(String content, boolean correct) {
    }

    /**
     * One generated question held in the preview session until the lecturer confirms it.
     *
     * @param type        {@code MCQ} or {@code MR}
     * @param content     plain-text question body
     * @param explanation optional rationale shown after grading; may be {@code null}
     * @param options     the 2..6 answer options
     */
    public record DraftQuestion(String type, String content, String explanation,
                                List<DraftOption> options) {
    }

    /**
     * The preview handed back to the browser after a successful generation.
     *
     * @param sessionId the in-memory session the confirm step refers back to
     * @param questions the generated drafts, in the order the model produced them
     */
    public record Preview(UUID sessionId, List<DraftQuestion> questions) {
    }

    /**
     * The lecturer's selection out of a preview.
     *
     * <p>{@code sessionId} is carried as text rather than {@link UUID} on purpose: a
     * malformed id is a stale or hand-crafted client, not a server fault, and binding it
     * as a UUID would make Jackson fail the whole body before the handler runs — returning
     * a 500 HTML error page instead of the JSON envelope the panel expects. Parsing is done
     * where the session is looked up, so a malformed id takes the same "session expired"
     * path as an unknown one.
     *
     * @param sessionId the session id returned by generate, as sent by the browser
     * @param indexes   zero-based positions into {@link Preview#questions()} to keep
     */
    public record ConfirmRequest(String sessionId, List<Integer> indexes) {
    }

    /**
     * Outcome of a confirm call.
     *
     * @param insertedCount how many questions were appended to the exam
     */
    public record ConfirmResult(int insertedCount) {
    }
}
