package com.ulp.features.tests.service;

import com.ulp.features.tests.service.GradingService.AttemptTotals;
import com.ulp.features.tests.service.GradingService.GradeOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the all-or-nothing {@link GradingService}. */
class GradingServiceTest {

    private final GradingService grading = new GradingService();

    @Test
    void mcqCorrectOnExactSingleChoice() {
        GradeOutcome out = grading.gradeResponse(Set.of(10L), Set.of(10L), new BigDecimal("2.00"));
        assertTrue(out.correct());
        assertEquals(0, new BigDecimal("2.00").compareTo(out.pointsEarned()));
    }

    @Test
    void mcqIncorrectOnWrongChoice() {
        GradeOutcome out = grading.gradeResponse(Set.of(10L), Set.of(11L), new BigDecimal("2.00"));
        assertFalse(out.correct());
        assertEquals(0, BigDecimal.ZERO.compareTo(out.pointsEarned()));
    }

    @Test
    void mrSubsetIsIncorrect() {
        // Correct {A,C}, selected {A} → incorrect, 0 points.
        GradeOutcome out = grading.gradeResponse(Set.of(1L, 3L), Set.of(1L), new BigDecimal("3.00"));
        assertFalse(out.correct());
        assertEquals(0, BigDecimal.ZERO.compareTo(out.pointsEarned()));
    }

    @Test
    void mrSupersetIsIncorrect() {
        // Correct {A,C}, selected {A,B,C} → incorrect.
        GradeOutcome out = grading.gradeResponse(Set.of(1L, 3L), Set.of(1L, 2L, 3L), new BigDecimal("3.00"));
        assertFalse(out.correct());
    }

    @Test
    void mrExactMatchIsFullCredit() {
        GradeOutcome out = grading.gradeResponse(Set.of(1L, 3L), Set.of(3L, 1L), new BigDecimal("3.00"));
        assertTrue(out.correct());
        assertEquals(0, new BigDecimal("3.00").compareTo(out.pointsEarned()));
    }

    @Test
    void emptyCorrectSetNeverGradesCorrect() {
        GradeOutcome out = grading.gradeResponse(Set.of(), Set.of(), new BigDecimal("2.00"));
        assertFalse(out.correct());
    }

    @Test
    void aggregateSumsPointsAndCounts() {
        AttemptTotals totals = grading.aggregate(new BigDecimal("5.00"), new BigDecimal("8.00"), 3, 4);
        assertEquals(0, new BigDecimal("5.00").compareTo(totals.score()));
        assertEquals(0, new BigDecimal("8.00").compareTo(totals.totalPoints()));
        assertEquals(3, totals.correctCount());
        assertEquals(4, totals.totalQuestions());
    }
}
