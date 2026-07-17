package com.ulp.features.tests.repository;

import com.ulp.features.tests.entity.TestResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Repository for {@link TestResponse} — a student's answers within an attempt. */
public interface TestResponseRepository extends JpaRepository<TestResponse, Long> {

    /** Responses recorded for an attempt (result / review). */
    List<TestResponse> findByAttemptId(Long attemptId);

    /** True if any student answer references one of the given question ids. */
    boolean existsByQuestionIdIn(Collection<Long> questionIds);
}
