package com.ulp.features.tests.repository;

import com.ulp.features.tests.entity.QuestionOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Repository for {@link QuestionOption} rows belonging to a {@link com.ulp.features.tests.entity.Question}. */
public interface QuestionOptionRepository extends JpaRepository<QuestionOption, Long> {

    /** Options of one question in display order. */
    List<QuestionOption> findByQuestionIdOrderBySortOrderAscIdAsc(Long questionId);

    /** Options of several questions (batch load, avoids N+1). */
    List<QuestionOption> findByQuestionIdInOrderBySortOrderAscIdAsc(Collection<Long> questionIds);

    /** Deletes all options of the given questions (edit = full replacement). */
    void deleteByQuestionIdIn(Collection<Long> questionIds);
}
