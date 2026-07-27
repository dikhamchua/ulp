package com.ulp.features.ai.questiongen;

import com.ulp.entities.TestActivity;
import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.log.AiRequestLogger;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.ConfirmRequest;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.ConfirmResult;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftQuestion;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.GenerateRequest;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.Preview;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.service.ExamQuestionBankWriter;
import com.ulp.features.tests.service.TestActivityWriter;
import com.ulp.features.tests.support.TestAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.ulp.common.IConstant.MSG_QB_INSERT_LOCKED;

/**
 * Generates exam questions from lecturer-supplied material and appends the approved ones
 * to an owned exam.
 *
 * <p>Two-step by design, mirroring the question-bank import flow: generation produces an
 * in-memory preview only, and nothing reaches {@code questions} until the lecturer has
 * ticked what to keep. Unreviewed model output is never persisted.
 *
 * <p>Persistence is delegated to {@link ExamQuestionBankWriter#appendQuestions}, the same
 * writer the shared-bank insert uses, so generated questions land as exam-owned snapshots
 * with their HTML already sanitised.
 */
@Service
public class AiQuestionGenerationService {

    private static final String MSG_SESSION_EXPIRED =
            "Phiên sinh câu hỏi đã hết hạn, vui lòng sinh lại";
    private static final String MSG_NO_SELECTION =
            "Vui lòng chọn ít nhất một câu hỏi để chèn vào bài test";

    private final TestAccessResolver accessResolver;
    private final ExamQuestionBankWriter questionBankWriter;
    private final DocumentTextExtractor textExtractor;
    private final AiQuestionPromptBuilder promptBuilder;
    private final AiQuestionResponseParser responseParser;
    private final AiQuestionDraftSessionStore sessionStore;
    private final AiClient aiClient;
    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;
    private final TestActivityWriter activityWriter;

    public AiQuestionGenerationService(TestAccessResolver accessResolver,
                                       ExamQuestionBankWriter questionBankWriter,
                                       DocumentTextExtractor textExtractor,
                                       AiQuestionPromptBuilder promptBuilder,
                                       AiQuestionResponseParser responseParser,
                                       AiQuestionDraftSessionStore sessionStore,
                                       AiClient aiClient,
                                       TestRepository testRepository,
                                       QuestionRepository questionRepository,
                                       TestActivityWriter activityWriter) {
        this.accessResolver = accessResolver;
        this.questionBankWriter = questionBankWriter;
        this.textExtractor = textExtractor;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.sessionStore = sessionStore;
        this.aiClient = aiClient;
        this.testRepository = testRepository;
        this.questionRepository = questionRepository;
        this.activityWriter = activityWriter;
    }

    /**
     * Generates question drafts from an uploaded file or pasted text.
     *
     * <p>Runs outside a write transaction: the provider call can take tens of seconds and
     * nothing is persisted here, so holding a database transaction open across it would
     * pin a connection for no benefit.
     *
     * @param userId  the acting lecturer
     * @param testId  the exam the drafts are destined for
     * @param file    an uploaded PDF/DOCX, or {@code null} when text was pasted
     * @param text    pasted material, used when no file was supplied
     * @param request the generation parameters
     * @return the preview, along with the session id needed to confirm it
     * @throws org.springframework.security.access.AccessDeniedException when the lecturer
     *         does not own the exam
     * @throws IllegalArgumentException when the exam is locked, the material cannot be
     *         read, or the model reply is unusable
     * @throws com.ulp.features.ai.client.AiClientException when no provider answered
     */
    public Preview generate(Long userId, Long testId, MultipartFile file, String text,
                            GenerateRequest request) {
        accessResolver.requireManageable(testId, userId);
        // Appending questions changes the bank shape, which is unsafe once graded —
        // check before spending tokens rather than after.
        if (questionBankWriter.hasStudentResponses(testId)) {
            throw new IllegalArgumentException(MSG_QB_INSERT_LOCKED);
        }

        String material = (file != null && !file.isEmpty())
                ? textExtractor.extract(file)
                : textExtractor.normalizePastedText(text);

        String reply = aiClient.chat(
                promptBuilder.systemPrompt(),
                promptBuilder.userMessage(request, material),
                AiQuestionPromptBuilder.maxTokensFor(request.count()),
                userId,
                AiRequestLogger.SOURCE_QUESTION_GEN);

        List<DraftQuestion> drafts = responseParser.parse(reply);
        AiQuestionDraftSession session = new AiQuestionDraftSession(
                UUID.randomUUID(), userId, testId, Instant.now(), drafts);
        sessionStore.save(session);
        return session.toPreview();
    }

    /**
     * Appends the chosen drafts to the exam as exam-owned snapshot rows.
     *
     * <p>The session is dropped once its drafts are inserted, so a resubmitted confirm
     * cannot duplicate them.
     *
     * @param userId  the acting lecturer
     * @param testId  the exam the drafts belong to
     * @param request the session id plus the indexes to keep
     * @return how many questions were appended
     * @throws org.springframework.security.access.AccessDeniedException when the lecturer
     *         does not own the exam
     * @throws IllegalArgumentException when the session is unknown/expired, the selection
     *         is empty, or the exam is locked
     */
    @Transactional
    public ConfirmResult confirm(Long userId, Long testId, ConfirmRequest request) {
        Test test = accessResolver.requireManageable(testId, userId);
        if (request == null) {
            throw new IllegalArgumentException(MSG_SESSION_EXPIRED);
        }
        AiQuestionDraftSession session = sessionStore.get(parseSessionId(request.sessionId()), userId)
                .filter(s -> s.getTestId().equals(testId))
                .orElseThrow(() -> new IllegalArgumentException(MSG_SESSION_EXPIRED));
        if (questionBankWriter.hasStudentResponses(testId)) {
            throw new IllegalArgumentException(MSG_QB_INSERT_LOCKED);
        }

        List<QuestionForm> selected =
                AiQuestionDraftSelector.select(session.getQuestions(), request.indexes());
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(MSG_NO_SELECTION);
        }

        int inserted = questionBankWriter.appendQuestions(testId, selected);
        test.setTotalQuestions(questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(testId).size());
        testRepository.save(test);
        activityWriter.write(test.getId(), TestActivity.TYPE_UPDATED,
                "Chèn " + inserted + " câu hỏi do AI sinh vào bài test \"" + test.getTitle() + "\"",
                null, userId);

        sessionStore.delete(session.getId());
        return new ConfirmResult(inserted);
    }

    /**
     * Turns the client-supplied session id into a {@link UUID}, or {@code null}.
     *
     * <p>A blank or malformed id is treated exactly like an id that is simply not in the
     * store: the lookup returns empty and the caller reports the session as expired. That
     * keeps a stale or hand-crafted client on the JSON error path instead of a parse
     * failure, and gives away nothing about which ids exist.
     *
     * @param raw the {@code sessionId} field of the confirm body; may be {@code null}
     * @return the parsed id, or {@code null} when it is absent or not a valid UUID
     */
    private static UUID parseSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
