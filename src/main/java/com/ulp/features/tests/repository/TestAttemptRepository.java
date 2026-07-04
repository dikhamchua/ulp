package com.ulp.features.tests.repository;

import com.ulp.features.tests.entity.TestAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Repository for {@link TestAttempt} — one student's run at one test. */
public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {

    /** The caller's open (IN_PROGRESS) attempt for a test, if any. */
    Optional<TestAttempt> findFirstByTestIdAndUserIdAndStatusOrderByStartedAtDesc(
            Long testId, Long userId, String status);

    /** All of the caller's attempts for a test, newest first. */
    List<TestAttempt> findByTestIdAndUserIdOrderByStartedAtDesc(Long testId, Long userId);

    /** The caller's attempts across several tests (readiness best-attempt scan). */
    List<TestAttempt> findByUserIdAndTestIdIn(Long userId, Collection<Long> testIds);

    /** Every attempt for a test (lecturer monitor / submissions). */
    List<TestAttempt> findByTestId(Long testId);

    /** Per-user guard: an attempt owned by the caller. */
    Optional<TestAttempt> findByIdAndUserId(Long id, Long userId);
}
