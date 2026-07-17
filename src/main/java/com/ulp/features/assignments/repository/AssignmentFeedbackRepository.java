package com.ulp.features.assignments.repository;

import com.ulp.features.assignments.entity.AssignmentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link AssignmentFeedback} entities.
 *
 * <p>The DB UNIQUE constraint on {@code submission_id} ensures at most one
 * feedback row per submission. The service upserts via find-then-update.
 */
public interface AssignmentFeedbackRepository extends JpaRepository<AssignmentFeedback, Long> {

    /**
     * Finds the feedback for a specific submission, if graded.
     *
     * @param submissionId the submission id
     * @return the feedback row, or empty if not yet graded
     */
    Optional<AssignmentFeedback> findBySubmissionId(Long submissionId);
}
