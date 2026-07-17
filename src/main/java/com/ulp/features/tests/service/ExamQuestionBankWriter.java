package com.ulp.features.tests.service;

import com.ulp.common.HtmlSanitizer;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.entity.Question;
import com.ulp.features.tests.entity.QuestionOption;
import com.ulp.features.tests.repository.QuestionOptionRepository;
import com.ulp.features.tests.repository.QuestionRepository;
import com.ulp.features.tests.repository.TestResponseRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.MSG_EXAM_QUESTION_BANK_LOCKED;

/**
 * Persists exam question banks for lecturer authoring. Owns full replacement
 * when the bank is unlocked, and in-place content updates when student
 * responses already exist (shape locked to keep FK rows valid).
 */
@Service
public class ExamQuestionBankWriter {

    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final TestResponseRepository responseRepository;

    public ExamQuestionBankWriter(QuestionRepository questionRepository,
                                  QuestionOptionRepository optionRepository,
                                  TestResponseRepository responseRepository) {
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.responseRepository = responseRepository;
    }

    /** True when any current question already has a student response. */
    public boolean hasStudentResponses(Long testId) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (existing.isEmpty()) return false;
        return responseRepository.existsByQuestionIdIn(
                existing.stream().map(Question::getId).toList());
    }

    /**
     * Loads options for the given questions grouped by question id, ordered
     * by sort order. Shared by edit form hydration and locked-bank updates.
     */
    public Map<Long, List<QuestionOption>> loadOptions(List<Question> questions) {
        if (questions.isEmpty()) return Map.of();
        List<Long> ids = questions.stream().map(Question::getId).toList();
        Map<Long, List<QuestionOption>> map = new HashMap<>();
        for (QuestionOption o : optionRepository.findByQuestionIdInOrderBySortOrderAscIdAsc(ids)) {
            map.computeIfAbsent(o.getQuestionId(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    /** Deletes existing questions/options and inserts the submitted set in order. */
    public void replaceQuestions(Long testId, List<QuestionForm> questions) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (!existing.isEmpty()) {
            optionRepository.deleteByQuestionIdIn(existing.stream().map(Question::getId).toList());
            questionRepository.deleteByTestId(testId);
        }
        int order = 1;
        for (QuestionForm qf : questions) {
            String contentHtml = HtmlSanitizer.sanitize(qf.content());
            Question q = new Question(testId, defaultQuestionType(qf.type()), contentHtml,
                    trimToNull(qf.explanation()), qf.points(), order++);
            Long qId = questionRepository.save(q).getId();
            int optOrder = 1;
            for (OptionForm of : qf.options()) {
                String optionHtml = HtmlSanitizer.sanitize(of.content());
                optionRepository.save(new QuestionOption(qId, optionHtml, of.correct(), optOrder++));
            }
        }
    }

    /**
     * Updates content of an existing question bank without deleting rows.
     * Shape (counts + ids) must match exactly; otherwise reject so graded
     * responses keep pointing at stable option ids.
     */
    public void updateQuestionContentsInPlace(Long testId, List<QuestionForm> questions) {
        List<Question> existing = questionRepository.findByTestIdOrderBySortOrderAscIdAsc(testId);
        if (existing.size() != questions.size()) {
            throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
        }
        Map<Long, List<QuestionOption>> optionsByQuestion = loadOptions(existing);
        for (int i = 0; i < existing.size(); i++) {
            Question q = existing.get(i);
            QuestionForm qf = questions.get(i);
            // Require stable ids when the bank is locked (client must round-trip them).
            if (qf.id() != null && !qf.id().equals(q.getId())) {
                throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
            }
            List<QuestionOption> opts = optionsByQuestion.getOrDefault(q.getId(), List.of());
            List<OptionForm> optForms = qf.options() == null ? List.of() : qf.options();
            if (opts.size() != optForms.size()) {
                throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
            }
            q.updateContent(defaultQuestionType(qf.type()),
                    HtmlSanitizer.sanitize(qf.content()),
                    trimToNull(qf.explanation()),
                    qf.points(),
                    i + 1);
            questionRepository.save(q);
            for (int j = 0; j < opts.size(); j++) {
                QuestionOption o = opts.get(j);
                OptionForm of = optForms.get(j);
                if (of.id() != null && !of.id().equals(o.getId())) {
                    throw new IllegalArgumentException(MSG_EXAM_QUESTION_BANK_LOCKED);
                }
                o.updateContent(HtmlSanitizer.sanitize(of.content()), of.correct(), j + 1);
                optionRepository.save(o);
            }
        }
    }

    private static String defaultQuestionType(String type) {
        return Question.TYPE_MR.equals(type) ? Question.TYPE_MR : Question.TYPE_MCQ;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
