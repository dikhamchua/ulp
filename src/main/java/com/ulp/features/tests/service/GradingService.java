package com.ulp.features.tests.service;

import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All-or-nothing, server-side grading. Never trusts any client notion of
 * correctness — correctness is recomputed from {@code is_correct} option flags.
 *
 * <ul>
 *   <li><b>MCQ</b> — correct iff the single selected option is the one correct
 *       option (exact-set equality with a size-1 correct set).</li>
 *   <li><b>MR</b> — correct iff the selected option-id set equals the correct
 *       option-id set exactly (no partial credit).</li>
 * </ul>
 */
@Service
public class GradingService {

    /** The outcome of grading one response. */
    public record GradeOutcome(boolean correct, BigDecimal pointsEarned) {
    }

    /** The aggregated totals for a whole attempt. */
    public record AttemptTotals(BigDecimal score, BigDecimal totalPoints,
                                int correctCount, int totalQuestions) {
    }

    /**
     * Grades one response given the question's correct option ids, the student's
     * selected ids, and the question's points. Correct → full points, else 0.
     * An empty correct set (malformed question) never grades as correct.
     */
    public GradeOutcome gradeResponse(Set<Long> correctIds, Set<Long> selectedIds, BigDecimal points) {
        boolean correct = !correctIds.isEmpty() && correctIds.equals(selectedIds);
        BigDecimal earned = correct ? nonNull(points) : BigDecimal.ZERO;
        return new GradeOutcome(correct, earned);
    }

    /** Convenience overload that derives the correct set from the question's options. */
    public GradeOutcome gradeQuestion(Question question, List<QuestionOption> options, Set<Long> selectedIds) {
        Set<Long> correctIds = options.stream()
                .filter(QuestionOption::isCorrect)
                .map(QuestionOption::getId)
                .collect(Collectors.toSet());
        return gradeResponse(correctIds, selectedIds, question.getPoints());
    }

    /**
     * Aggregates an attempt from the per-question points and the earned points.
     * {@code totalPointsSum} is the sum of question points; {@code earnedSum} the
     * sum of points earned; {@code correctCount}/{@code totalQuestions} the tallies.
     */
    public AttemptTotals aggregate(BigDecimal earnedSum, BigDecimal totalPointsSum,
                                   int correctCount, int totalQuestions) {
        return new AttemptTotals(nonNull(earnedSum), nonNull(totalPointsSum),
                correctCount, totalQuestions);
    }

    private static BigDecimal nonNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
