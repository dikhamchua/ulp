package com.ulp.features.tests.support;

import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;
import com.ulp.features.tests.entity.Question;

import java.util.List;

import static com.ulp.common.IConstant.MSG_EXAM_NEEDS_CLASS;
import static com.ulp.common.IConstant.MSG_EXAM_NEEDS_QUESTIONS;
import static com.ulp.common.IConstant.MSG_EXAM_TITLE_BLANK;
import static com.ulp.common.IConstant.MSG_MCQ_ONE_CORRECT;
import static com.ulp.common.IConstant.MSG_QUESTION_NEEDS_CORRECT;
import static com.ulp.common.IConstant.MSG_QUESTION_NEEDS_OPTIONS;

/**
 * Validates a lecturer exam form before any persistence. Rules: title + class
 * required, at least one question, each question needs ≥2 options and ≥1 correct,
 * and an MCQ needs exactly one correct option. Throws
 * {@link IllegalArgumentException} (→ 400 / field toast) on the first violation.
 */
public final class ExamFormValidator {

    private ExamFormValidator() {
        // utility holder
    }

    /** Validates the form; throws {@link IllegalArgumentException} on the first error. */
    public static void validate(ExamForm form) {
        if (isBlank(form.title())) {
            throw new IllegalArgumentException(MSG_EXAM_TITLE_BLANK);
        }
        if (form.classId() == null) {
            throw new IllegalArgumentException(MSG_EXAM_NEEDS_CLASS);
        }
        List<QuestionForm> questions = form.questions();
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException(MSG_EXAM_NEEDS_QUESTIONS);
        }
        for (QuestionForm q : questions) {
            validateQuestion(q);
        }
    }

    private static void validateQuestion(QuestionForm q) {
        List<OptionForm> options = q.options();
        if (options == null || options.size() < 2) {
            throw new IllegalArgumentException(MSG_QUESTION_NEEDS_OPTIONS);
        }
        long correct = options.stream().filter(OptionForm::correct).count();
        if (correct < 1) {
            throw new IllegalArgumentException(MSG_QUESTION_NEEDS_CORRECT);
        }
        // MCQ (single-response) must have exactly one correct option.
        if (Question.TYPE_MCQ.equals(q.type()) && correct != 1) {
            throw new IllegalArgumentException(MSG_MCQ_ONE_CORRECT);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
