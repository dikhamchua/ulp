package com.ulp.features.tests.service;

import com.ulp.features.tests.dto.TestDtos.ResultView;
import com.ulp.features.tests.dto.TestDtos.ReviewOptionView;
import com.ulp.features.tests.dto.TestDtos.ReviewQuestionView;
import com.ulp.features.tests.dto.TestDtos.ReviewView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.entity.TestResponse;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestResponseRepository;
import com.ulp.features.tests.support.OptionIdsCodec;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the post-submit result summary and the per-question review from a
 * graded attempt. Access is the caller's responsibility — this builder only
 * shapes data (used by both the student review and the lecturer submissions
 * review).
 */
@Service
public class AttemptResultBuilder {

    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestResponseRepository responseRepository;

    public AttemptResultBuilder(QuestionRepository questionRepository,
                                QuestionOptionRepository optionRepository,
                                TestResponseRepository responseRepository) {
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.responseRepository = responseRepository;
    }

    /** The numeric result summary + pass/fail decision. */
    public ResultView buildResult(Test test, TestAttempt attempt) {
        BigDecimal score = nz(attempt.getScore());
        BigDecimal passing = test.getPassingScore();
        boolean hasThreshold = passing != null;
        boolean passed = hasThreshold && score.compareTo(passing) >= 0;
        return new ResultView(test.getId(), attempt.getId(), test.getTitle(),
                score, nz(attempt.getTotalPoints()), hasThreshold, passed, passing,
                nzInt(attempt.getCorrectCount()), nzInt(attempt.getTotalQuestions()),
                nzInt(attempt.getTimeSpentSeconds()), attempt.getStatus());
    }

    /** The per-question review: student answer, correct answer, correctness, explanation. */
    public ReviewView buildReview(Test test, TestAttempt attempt,
                                  boolean lecturerView, String studentName) {
        List<Question> questions = questionRepository
                .findByTestIdOrderBySortOrderAscIdAsc(test.getId());
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(questions);
        Map<Long, TestResponse> responsesByQuestion = responseRepository
                .findByAttemptId(attempt.getId()).stream()
                .collect(Collectors.toMap(TestResponse::getQuestionId, r -> r, (a, b) -> b));

        List<ReviewQuestionView> views = new ArrayList<>();
        for (Question q : questions) {
            TestResponse resp = responsesByQuestion.get(q.getId());
            Set<Long> selected = resp == null ? Set.of()
                    : OptionIdsCodec.parse(resp.getSelectedOptionIds());
            boolean correct = resp != null && Boolean.TRUE.equals(resp.getCorrect());
            List<ReviewOptionView> optViews = new ArrayList<>();
            for (QuestionOption o : optionsByQuestion.getOrDefault(q.getId(), List.of())) {
                optViews.add(new ReviewOptionView(o.getId(), o.getContent(),
                        o.isCorrect(), selected.contains(o.getId())));
            }
            views.add(new ReviewQuestionView(q.getId(), q.getQuestionType(), q.getContent(),
                    q.getExplanation(), correct, optViews));
        }
        return new ReviewView(test.getId(), test.getClassId(), attempt.getId(), test.getTitle(),
                nzInt(attempt.getCorrectCount()), nzInt(attempt.getTotalQuestions()),
                nz(attempt.getScore()), lecturerView, studentName, views);
    }

    private Map<Long, List<QuestionOption>> loadOptions(List<Question> questions) {
        if (questions.isEmpty()) return Map.of();
        List<Long> ids = questions.stream().map(Question::getId).toList();
        return optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }
}
