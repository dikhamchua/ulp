package com.ulp.features.progress.repository;

import com.ulp.entities.LearningProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link LearningProgress} (ULP-4.5).
 *
 * <p>{@link LearningProgress} has no {@code @SQLRestriction} (the table has
 * no soft-delete column), so every method sees all rows.
 */
public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {

    /**
     * Returns the (at-most-one) progress row for a (user, lesson) pair. The
     * unique key {@code idx_lp_user_lesson} guarantees at most one row.
     */
    Optional<LearningProgress> findByUserIdAndLessonId(Long userId, Long lessonId);

    /**
     * Returns the ids of the lessons the user has COMPLETED among the given
     * candidate lesson ids. Used to compute per-section / class aggregates in
     * a single query. Callers MUST pass a non-empty collection.
     */
    @Query("SELECT lp.lessonId FROM LearningProgress lp "
            + "WHERE lp.userId = :userId AND lp.status = 'COMPLETED' "
            + "AND lp.lessonId IN :lessonIds")
    List<Long> findCompletedLessonIds(@Param("userId") Long userId,
                                      @Param("lessonIds") Collection<Long> lessonIds);

    /**
     * Cohort aggregate for the lecturer progress dashboard: one row per user who
     * has any progress against the given (class-scoped, PUBLISHED) lesson ids.
     * {@code completedCount} counts only COMPLETED rows while {@code lastActivity}
     * spans ANY status (a still-in-progress open still counts as activity).
     *
     * <p>Runs once for the whole cohort — NOT per student — so the dashboard has
     * no N+1 (design D2). Callers MUST pass a non-empty collection: an empty
     * {@code IN ()} is invalid SQL, so the service skips this call when the class
     * has no published lessons.
     */
    @Query("SELECT lp.userId AS userId, "
            + "SUM(CASE WHEN lp.status = 'COMPLETED' THEN 1 ELSE 0 END) AS completedCount, "
            + "MAX(lp.updatedAt) AS lastActivity "
            + "FROM LearningProgress lp "
            + "WHERE lp.lessonId IN :lessonIds "
            + "GROUP BY lp.userId")
    List<ProgressAggregate> aggregateByLessonIds(@Param("lessonIds") Collection<Long> lessonIds);

    /**
     * Returns the (at-most per lesson) progress rows for one student scoped to
     * the given lesson ids. Powers the per-student drill-down status map. Callers
     * MUST pass a non-empty collection.
     */
    List<LearningProgress> findByUserIdAndLessonIdIn(Long userId, Collection<Long> lessonIds);

    /**
     * Returns COMPLETED (userId, lessonId) pairs among the given lesson ids.
     * Used by the teaching dashboard so completed counts can be regrouped
     * per-class (cross-class {@link #aggregateByLessonIds} would over-count).
     * Callers MUST pass a non-empty collection.
     */
    @Query("SELECT lp.userId AS userId, lp.lessonId AS lessonId FROM LearningProgress lp "
            + "WHERE lp.status = 'COMPLETED' AND lp.lessonId IN :lessonIds")
    List<UserLessonId> findCompletedUserLessonPairs(
            @Param("lessonIds") Collection<Long> lessonIds);

    /** Projection for {@link #findCompletedUserLessonPairs}. */
    interface UserLessonId {
        Long getUserId();
        Long getLessonId();
    }

    /**
     * Spring Data interface projection for {@link #aggregateByLessonIds}. Getter
     * names map to the query aliases ({@code userId}, {@code completedCount},
     * {@code lastActivity}).
     */
    interface ProgressAggregate {
        Long getUserId();

        Long getCompletedCount();

        LocalDateTime getLastActivity();
    }
}
