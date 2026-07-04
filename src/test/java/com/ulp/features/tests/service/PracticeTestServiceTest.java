package com.ulp.features.tests.service;

import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.TestDtos.PracticeForm;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.support.TestAccessQueries;
import com.ulp.features.tests.support.TestAccessResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PracticeTestService} sampling, copy and count clamp. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PracticeTestServiceTest {

    @Mock private TestRepository testRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private QuestionOptionRepository optionRepository;
    @Mock private TestAccessQueries accessQueries;
    @Mock private TestAccessResolver accessResolver;
    @Mock private ClassRepository classRepository;

    private PracticeTestService service() {
        return new PracticeTestService(testRepository, questionRepository, optionRepository,
                accessQueries, accessResolver, classRepository);
    }

    private Question question(long id) {
        Question q = mock(Question.class);
        when(q.getId()).thenReturn(id);
        when(q.getQuestionType()).thenReturn(Question.TYPE_MCQ);
        when(q.getContent()).thenReturn("Q" + id);
        when(q.getExplanation()).thenReturn(null);
        when(q.getPoints()).thenReturn(BigDecimal.ONE);
        return q;
    }

    @Test
    void clampsCountToPoolSizeAndCopiesQuestions() {
        // FQN for the entity — its simple name 'Test' clashes with JUnit's @Test.
        com.ulp.features.tests.entity.Test source = mock(com.ulp.features.tests.entity.Test.class);
        when(source.getId()).thenReturn(1L);
        when(source.isPractice()).thenReturn(false);
        when(accessQueries.accessibleExams(7L)).thenReturn(List.of(source));

        // Build the question mocks first — stubbing them inside another
        // when(...).thenReturn(...) triggers Mockito UnfinishedStubbing.
        Question q1 = question(11), q2 = question(12), q3 = question(13);
        Question copied = question(500);
        // Pool of 3 accessible questions; request 10 → clamp to 3.
        when(questionRepository.findByTestIdIn(anyList())).thenReturn(List.of(q1, q2, q3));
        when(optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(anyList()))
                .thenReturn(List.of(new QuestionOption(11L, "A", true, 1)));

        com.ulp.features.tests.entity.Test saved = mock(com.ulp.features.tests.entity.Test.class);
        when(saved.getId()).thenReturn(99L);
        when(testRepository.save(any(com.ulp.features.tests.entity.Test.class))).thenReturn(saved);
        when(questionRepository.save(any(Question.class))).thenReturn(copied);

        Long id = service().create(7L, new PracticeForm(null, null, 10));

        assertEquals(99L, id);
        // Exactly pool-size (3) questions copied, not the requested 10.
        verify(questionRepository, times(3)).save(any(Question.class));
    }

    @Test
    void emptyPoolThrows() {
        when(accessQueries.accessibleExams(7L)).thenReturn(List.of());
        when(questionRepository.findByTestIdIn(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service().create(7L, new PracticeForm(null, null, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
