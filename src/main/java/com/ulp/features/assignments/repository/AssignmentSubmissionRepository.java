package com.ulp.features.assignments.repository;

import com.ulp.features.assignments.entity.AssignmentSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link AssignmentSubmission} entities.
 *
 * <p>The DB UNIQUE index on (assignment_id, user_id) is exploited by the
 * service upsert: find-then-update or insert.
 */
public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, Long> {

    /**
     * Finds the single submission for a given (assignment, student) pair.
     * Used for upsert logic and "view own submission" screens.
     *
     * @param assignmentId the assignment id
     * @param userId       the student's user id
     * @return the submission, or empty if none yet
     */
    Optional<AssignmentSubmission> findByAssignmentIdAndUserId(Long assignmentId, Long userId);

    /**
     * Returns all submissions for a given assignment. Used by the lecturer's
     * submissions list.
     *
     * @param assignmentId the assignment id
     * @return all submissions for that assignment
     */
    @Query("SELECT s FROM AssignmentSubmission s WHERE s.assignmentId = :assignmentId ORDER BY s.submittedAt DESC")
    List<AssignmentSubmission> findAllByAssignmentId(@Param("assignmentId") Long assignmentId);
}
