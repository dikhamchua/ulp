package com.ulp.features.tests.service;

import com.ulp.features.tests.dto.TestDtos.ResultView;
import com.ulp.features.tests.dto.TestDtos.ReviewView;
import com.ulp.features.tests.dto.TestDtos.SubmitRequest;
import com.ulp.features.tests.dto.TestDtos.SubmitResult;
import com.ulp.features.tests.dto.TestDtos.TakeView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.entity.TestResponse;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.repository.TestResponseRepository;
import com.ulp.features.tests.service.GradingService.GradeOutcome;
import com.ulp.features.tests.support.ExamDeadline;
import com.ulp.features.tests.support.OptionIdsCodec;
import com.ulp.features.tests.support.TestAccessResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Attempt lifecycle: start/resume, taking view, heartbeat, and graded submit. */
@Service
public class TestAttemptService {

    private final TestRepository testRepository;
    private final TestAttemptRepository attemptRepository;
    private final TestResponseRepository responseRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestAccessResolver accessResolver;
    private final GradingService gradingService;
    private final TakeViewBuilder takeViewBuilder;
    private final AttemptResultBuilder resultBuilder;

    public TestAttemptService(TestRepository testRepository,
                              TestAttemptRepository attemptRepository,
                              TestResponseRepository responseRepository,
                              QuestionRepository questionRepository,
                              QuestionOptionRepository optionRepository,
                              TestAccessResolver accessResolver,
                              GradingService gradingService,
                              TakeViewBuilder takeViewBuilder,
                              AttemptResultBuilder resultBuilder) {
        this.testRepository = testRepository;
        this.attemptRepository = attemptRepository;
        this.responseRepository = responseRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessResolver = accessResolver;
        this.gradingService = gradingService;
        this.takeViewBuilder = takeViewBuilder;
        this.resultBuilder = resultBuilder;
    }

    /** Owner-only result summary for a submitted attempt. */
    @Transactional(readOnly = true)
    public ResultView result(Long testId, Long attemptId, Long userId) {
        TestAttempt attempt = requireAttemptOfTest(testId, attemptId, userId);
        Test test = loadTest(attempt.getTestId());
        return resultBuilder.buildResult(test, attempt);
    }

    /** Owner-only per-question review for a submitted attempt. */
    @Transactional(readOnly = true)
    public ReviewView review(Long testId, Long attemptId, Long userId) {
        TestAttempt attempt = requireAttemptOfTest(testId, attemptId, userId);
        Test test = loadTest(attempt.getTestId());
        return resultBuilder.buildReview(test, attempt, false, null);
    }

    private TestAttempt requireAttemptOfTest(Long testId, Long attemptId, Long userId) {
        TestAttempt attempt = accessResolver.requireOwnAttempt(attemptId, userId);
        // Guard against a mismatched {testId}/{attemptId} path pairing.
        if (!attempt.getTestId().equals(testId)) {
            throw new EntityNotFoundException(TestAccessResolver.ATTEMPT_NF_MSG);
        }
        return attempt;
    }

    private Test loadTest(Long testId) {
        return testRepository.findById(testId)
                .orElseThrow(() -> new EntityNotFoundException(TestAccessResolver.NF_MSG));
    }

    /**
     * Starts a new attempt or resumes the caller's open one. Access is checked
     * first (inaccessible → 404). At most one IN_PROGRESS attempt exists per
     * (test, user); a second start reuses it.
     */
    @Transactional
    public TakeView startOrResume(Long testId, Long userId) {
        Test test = accessResolver.requireAttemptable(testId, userId);
        TestAttempt attempt = attemptRepository
                .findFirstByTestIdAndUserIdAndStatusOrderByStartedAtDesc(
                        testId, userId, TestAttempt.STATUS_IN_PROGRESS)
                .orElseGet(() -> attemptRepository.save(new TestAttempt(testId, userId)));
        return takeViewBuilder.build(test, attempt);
    }

    /** Updates {@code last_activity_at} for a live attempt; owner-only. No-op when closed. */
    @Transactional
    public void heartbeat(Long attemptId, Long userId) {
        TestAttempt attempt = accessResolver.requireOwnAttempt(attemptId, userId);
        if (attempt.isInProgress()) {
            attempt.touchActivity();
            attemptRepository.save(attempt);
        }
    }

    /**
     * Grades and closes an attempt. The server recomputes the deadline: past it →
     * {@code TIMED_OUT}, otherwise {@code SUBMITTED}; either way only the submitted
     * responses are graded. Already-closed attempts are returned unchanged.
     */
    @Transactional
    public SubmitResult submit(Long attemptId, Long userId, SubmitRequest request) {
        TestAttempt attempt = accessResolver.requireOwnAttempt(attemptId, userId);
        if (!attempt.isInProgress()) {
            return new SubmitResult(attempt.getTestId(), attempt.getId(), attempt.getStatus());
        }
        Test test = testRepository.findById(attempt.getTestId())
                .orElseThrow(() -> new EntityNotFoundException(TestAccessResolver.NF_MSG));

        List<Question> questions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(test.getId());
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(questions);
        Map<Long, List<Long>> answers = indexAnswers(request);

        BigDecimal earnedSum = BigDecimal.ZERO;
        BigDecimal totalPointsSum = BigDecimal.ZERO;
        int correctCount = 0;
        for (Question q : questions) {
            totalPointsSum = totalPointsSum.add(nonNull(q.getPoints()));
            Set<Long> selected = OptionIdsCodec.fromList(answers.get(q.getId()));
            GradeOutcome outcome = gradingService.gradeQuestion(
                    q, optionsByQuestion.getOrDefault(q.getId(), List.of()), selected);
            if (outcome.correct()) correctCount++;
            earnedSum = earnedSum.add(outcome.pointsEarned());
            persistResponse(attempt.getId(), q.getId(), selected, outcome);
        }

        String finalStatus = ExamDeadline.isPastDeadline(test, attempt, LocalDateTime.now())
                ? TestAttempt.STATUS_TIMED_OUT : TestAttempt.STATUS_SUBMITTED;
        int timeSpent = (int) Math.max(0,
                Duration.between(attempt.getStartedAt(), LocalDateTime.now()).getSeconds());
        attempt.finalizeGrade(earnedSum, totalPointsSum, correctCount, questions.size(),
                timeSpent, finalStatus);
        attemptRepository.save(attempt);
        return new SubmitResult(test.getId(), attempt.getId(), finalStatus);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void persistResponse(Long attemptId, Long questionId, Set<Long> selected,
                                 GradeOutcome outcome) {
        Set<Long> canonical = OptionIdsCodec.sorted(selected);
        TestResponse response = new TestResponse(attemptId, questionId,
                OptionIdsCodec.toJson(canonical));
        response.grade(outcome.correct(), outcome.pointsEarned());
        responseRepository.save(response);
    }

    private Map<Long, List<Long>> indexAnswers(SubmitRequest request) {
        if (request == null || request.answers() == null) return Map.of();
        return request.answers().stream()
                .filter(a -> a.questionId() != null)
                .collect(Collectors.toMap(
                        a -> a.questionId(),
                        a -> a.selectedOptionIds() == null ? List.of() : a.selectedOptionIds(),
                        (a, b) -> b));
    }

    private Map<Long, List<QuestionOption>> loadOptions(List<Question> questions) {
        if (questions.isEmpty()) return Map.of();
        List<Long> ids = questions.stream().map(Question::getId).toList();
        return optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
