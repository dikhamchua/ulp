package com.ulp.features.tests.service;

import com.ulp.features.tests.dto.TestDtos.ReadinessExamRow;
import com.ulp.features.tests.dto.TestDtos.ReadinessView;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.support.TestAccessQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.BAND_NOT_READY;
import static com.ulp.common.IConstant.BAND_OK;
import static com.ulp.common.IConstant.BAND_READY;

/**
 * Derives (never stores) the exam readiness score: the mean of best-attempt
 * score-percentages across the accessible MOCK/MODULE exams, where an untaken
 * exam contributes 0% (so coverage, not just performance, moves the number).
 */
@Service
public class ReadinessService {

    private final TestAccessQueries accessQueries;
    private final TestAttemptRepository attemptRepository;

    public ReadinessService(TestAccessQueries accessQueries,
                            TestAttemptRepository attemptRepository) {
        this.accessQueries = accessQueries;
        this.attemptRepository = attemptRepository;
    }

    /** Computes the readiness view-model for the student. */
    @Transactional(readOnly = true)
    public ReadinessView compute(Long userId) {
        List<Test> exams = accessQueries.accessibleGradedExams(userId);
        Map<Long, Integer> bestByTest = bestPercentByTest(userId, exams);

        List<ReadinessExamRow> rows = new ArrayList<>();
        int sum = 0;
        int done = 0;
        for (Test t : exams) {
            Integer best = bestByTest.get(t.getId());
            boolean isDone = best != null;
            int pct = isDone ? best : 0;
            if (isDone) done++;
            sum += pct;
            rows.add(new ReadinessExamRow(t.getId(), t.getTitle(), isDone, pct));
        }

        int score = exams.isEmpty() ? 0 : Math.round((float) sum / exams.size());
        return new ReadinessView(score, band(score), done, exams.size(), rows);
    }

    /** Band label for a 0–100 score: <50 not-ready, 50–79 ok, ≥80 ready. */
    public static String band(int score) {
        if (score >= 80) return BAND_READY;
        if (score >= 50) return BAND_OK;
        return BAND_NOT_READY;
    }

    private Map<Long, Integer> bestPercentByTest(Long userId, List<Test> exams) {
        Map<Long, Integer> best = new HashMap<>();
        if (exams.isEmpty()) return best;
        List<Long> testIds = exams.stream().map(Test::getId).toList();
        for (TestAttempt a : attemptRepository.findByUserIdAndTestIdIn(userId, testIds)) {
            if (a.isInProgress() || a.getScore() == null || a.getTotalPoints() == null
                    || a.getTotalPoints().signum() <= 0) {
                continue;
            }
            int pct = a.getScore().multiply(BigDecimal.valueOf(100))
                    .divide(a.getTotalPoints(), 0, RoundingMode.HALF_UP).intValue();
            best.merge(a.getTestId(), pct, Math::max);
        }
        return best;
    }
}
