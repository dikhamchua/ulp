package com.ulp.features.tests.service;

import com.ulp.features.tests.dto.TestDtos.ReadinessView;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.support.TestAccessQueries;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static com.ulp.common.IConstant.BAND_NOT_READY;
import static com.ulp.common.IConstant.BAND_OK;
import static com.ulp.common.IConstant.BAND_READY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the derived {@link ReadinessService}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadinessServiceTest {

    @Mock
    private TestAccessQueries accessQueries;
    @Mock
    private TestAttemptRepository attemptRepository;

    private ReadinessService service() {
        return new ReadinessService(accessQueries, attemptRepository);
    }

    @org.junit.jupiter.api.Test
    void bandBoundaries() {
        assertEquals(BAND_NOT_READY, ReadinessService.band(49));
        assertEquals(BAND_OK, ReadinessService.band(50));
        assertEquals(BAND_OK, ReadinessService.band(79));
        assertEquals(BAND_READY, ReadinessService.band(80));
    }

    @org.junit.jupiter.api.Test
    void untakenExamPullsScoreDown() {
        Test exam1 = mock(Test.class);
        when(exam1.getId()).thenReturn(1L);
        when(exam1.getTitle()).thenReturn("MOCK 1");
        Test exam2 = mock(Test.class);
        when(exam2.getId()).thenReturn(2L);
        when(exam2.getTitle()).thenReturn("MODULE 1");
        when(accessQueries.accessibleGradedExams(any())).thenReturn(List.of(exam1, exam2));

        TestAttempt a1 = mock(TestAttempt.class);
        when(a1.getTestId()).thenReturn(1L);
        when(a1.isInProgress()).thenReturn(false);
        when(a1.getScore()).thenReturn(new BigDecimal("10.00"));
        when(a1.getTotalPoints()).thenReturn(new BigDecimal("10.00"));
        when(attemptRepository.findByUserIdAndTestIdIn(eq(7L), any())).thenReturn(List.of(a1));

        ReadinessView view = service().compute(7L);

        // Best 100% on one exam, 0% on the untaken one → mean 50.
        assertEquals(50, view.score());
        assertEquals(BAND_OK, view.band());
        assertEquals(1, view.doneCount());
        assertEquals(2, view.totalCount());
        assertThat(view.exams()).hasSize(2);
    }
}
