package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.tests.dto.TestDtos.PracticeForm;
import com.ulp.features.tests.dto.TestDtos.PracticeSource;
import com.ulp.features.tests.dto.TestDtos.PracticeView;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.support.TestAccessQueries;
import com.ulp.features.tests.support.TestAccessResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ulp.common.IConstant.MSG_PRACTICE_EMPTY_POOL;

/**
 * Generates a personal PRACTICE test by random-sampling MCQ/MR questions from
 * the accessible published pool and copying each question + its options into a
 * new test owned only by the student (schema-honest: questions belong to one
 * test, so practice gets its own rows).
 */
@Service
public class PracticeTestService {

    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestAccessQueries accessQueries;
    private final TestAccessResolver accessResolver;
    private final ClassRepository classRepository;

    public PracticeTestService(TestRepository testRepository,
                               QuestionRepository questionRepository,
                               QuestionOptionRepository optionRepository,
                               TestAccessQueries accessQueries,
                               TestAccessResolver accessResolver,
                               ClassRepository classRepository) {
        this.testRepository = testRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.accessQueries = accessQueries;
        this.accessResolver = accessResolver;
        this.classRepository = classRepository;
    }

    /** Source options (accessible classes + exams) for the practice-new form. */
    @Transactional(readOnly = true)
    public PracticeView sources(Long userId) {
        List<Test> exams = accessQueries.accessibleExams(userId).stream()
                .filter(t -> !t.isPractice()).toList();
        List<PracticeSource> examOpts = exams.stream()
                .map(t -> new PracticeSource(t.getId(), t.getTitle(), false)).toList();
        List<Long> classIds = exams.stream().map(Test::getClassId)
                .filter(id -> id != null).distinct().toList();
        List<PracticeSource> classOpts = new ArrayList<>();
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            classOpts.add(new PracticeSource(c.getId(), c.getName(), true));
        }
        return new PracticeView(classOpts, examOpts);
    }

    /**
     * Creates a PRACTICE test for the student from the requested source scope.
     * Draws only accessible questions; clamps the count to the pool size; throws
     * when the pool is empty. Returns the new test id.
     */
    @Transactional
    public Long create(Long userId, PracticeForm form) {
        List<Test> sourceTests = resolveSources(userId, form);
        List<Long> testIds = sourceTests.stream().map(Test::getId).toList();
        List<Question> pool = testIds.isEmpty() ? List.of()
                : new ArrayList<>(questionRepository.findByTestIdIn(testIds));
        if (pool.isEmpty()) {
            throw new IllegalArgumentException(MSG_PRACTICE_EMPTY_POOL);
        }

        int count = Math.min(Math.max(form.count(), 1), pool.size());
        Collections.shuffle(pool);
        List<Question> sample = pool.subList(0, count);

        Test practice = new Test(userId, Test.TYPE_PRACTICE);
        practice.setTitle("Luyện tập - " + count + " câu hỏi");
        practice.setStatus(Test.STATUS_PUBLISHED);
        practice.setTimeMode(Test.TIME_MODE_FIXED_WINDOW);
        practice.setTotalQuestions(count);
        Long practiceId = testRepository.save(practice).getId();

        copyQuestions(practiceId, sample);
        return practiceId;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Resolves the accessible source tests for the requested scope. */
    private List<Test> resolveSources(Long userId, PracticeForm form) {
        if (form.sourceTestId() != null) {
            // Access-checked: throws 404 if the student cannot take this exam.
            Test test = accessResolver.requireAttemptable(form.sourceTestId(), userId);
            return List.of(test);
        }
        List<Test> accessible = accessQueries.accessibleExams(userId).stream()
                .filter(t -> !t.isPractice()).toList();
        if (form.sourceClassId() != null) {
            return accessible.stream()
                    .filter(t -> form.sourceClassId().equals(t.getClassId())).toList();
        }
        return accessible;
    }

    /** Deep-copies sampled questions + their options into the new practice test. */
    private void copyQuestions(Long practiceId, List<Question> sample) {
        List<Long> ids = sample.stream().map(Question::getId).toList();
        Map<Long, List<QuestionOption>> optionsByQuestion = optionRepository
                .findByQuestionIdInOrderBySortOrderAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        int order = 1;
        for (Question src : sample) {
            Question copy = new Question(practiceId, src.getQuestionType(), src.getContent(),
                    src.getExplanation(), nz(src.getPoints()), order++);
            Long copyId = questionRepository.save(copy).getId();
            int optOrder = 1;
            for (QuestionOption o : optionsByQuestion.getOrDefault(src.getId(), List.of())) {
                optionRepository.save(new QuestionOption(copyId, o.getContent(),
                        o.isCorrect(), optOrder++));
            }
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ONE : v;
    }
}
