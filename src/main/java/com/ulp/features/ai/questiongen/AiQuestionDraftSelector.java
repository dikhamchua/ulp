package com.ulp.features.ai.questiongen;

import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftOption;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.DraftQuestion;
import com.ulp.features.tests.dto.LecturerTestDtos.OptionForm;
import com.ulp.features.tests.dto.LecturerTestDtos.QuestionForm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Maps the drafts a lecturer ticked in the preview onto the exam writer's form shape.
 *
 * <p>Kept apart from {@code AiQuestionGenerationService} because it is pure translation:
 * no access checks, no persistence, no provider call. That makes it directly unit-testable
 * and keeps the service focused on the two-step generate/confirm policy.
 */
final class AiQuestionDraftSelector {

    private AiQuestionDraftSelector() {
    }

    /** Score assigned to every generated question; lecturers retune it in the builder. */
    private static final BigDecimal DEFAULT_POINTS = BigDecimal.ONE;

    /**
     * Selects the ticked drafts, in the order they were ticked.
     *
     * <p>Out-of-range indexes are skipped rather than rejected, and duplicates collapse, so
     * a stale checkbox in the browser cannot insert the same draft twice or fail the whole
     * request.
     *
     * @param drafts  the full preview the session holds
     * @param indexes the zero-based positions the lecturer kept, possibly null or dirty
     * @return the selected drafts as writer forms; empty when nothing usable was ticked
     */
    static List<QuestionForm> select(List<DraftQuestion> drafts, List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        List<QuestionForm> forms = new ArrayList<>();
        for (Integer index : new LinkedHashSet<>(indexes)) {
            if (index == null || index < 0 || index >= drafts.size()) {
                continue;
            }
            forms.add(toForm(drafts.get(index)));
        }
        return forms;
    }

    /** Converts one draft, carrying its options across with their correct flags intact. */
    private static QuestionForm toForm(DraftQuestion draft) {
        List<OptionForm> options = new ArrayList<>();
        for (DraftOption option : draft.options()) {
            options.add(new OptionForm(null, option.content(), option.correct()));
        }
        return new QuestionForm(null, draft.type(), draft.content(),
                draft.explanation(), DEFAULT_POINTS, options);
    }
}
