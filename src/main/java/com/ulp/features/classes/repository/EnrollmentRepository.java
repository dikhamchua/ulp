package com.ulp.features.classes.repository;

import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Enrollment} entities.
 *
 * <p>Provides read-oriented queries needed by Sprint 2 to render the member
 * list on the class detail page, plus the join/list queries added by Sprint
 * 2.3 for the {@code /my/classes} surface.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /**
     * Returns all enrollments for a given class with the specified status,
     * ordered by {@code joined_at} descending (most recent first).
     *
     * <p>Uses {@code JOIN FETCH} to eagerly load the associated {@link com.ulp.entities.User}
     * in the same query, avoiding the N+1 SELECT problem when callers access
     * {@code enrollment.getUser()} for each row.
     *
     * @param classId the ID of the class
     * @param status  the enrollment status to filter by (e.g. {@code "ACTIVE"})
     * @return list of matching {@link Enrollment} records with {@code user} pre-initialized
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.user " +
            "WHERE e.classId = :classId AND e.status = :status " +
            "ORDER BY e.joinedAt DESC")
    List<Enrollment> findAllByClassIdAndStatusOrderByJoinedAtDesc(@Param("classId") Long classId,
                                                                  @Param("status") String status);

    /**
     * Counts the number of enrollments for a given class with the specified status.
     *
     * @param classId the ID of the class
     * @param status  the enrollment status to filter by (e.g. {@code "ACTIVE"})
     * @return the count of matching enrollments
     */
    long countByClassIdAndStatus(Long classId, String status);

    /**
     * Batch ACTIVE enrollment counts grouped by class id. Callers MUST pass a
     * non-empty collection (empty {@code IN ()} is invalid SQL).
     *
     * @param classIds the classes to count
     * @return one projection row per class that has at least one ACTIVE enrollment
     */
    @Query("SELECT e.classId AS classId, COUNT(e) AS cnt FROM Enrollment e "
            + "WHERE e.classId IN :classIds AND e.status = 'ACTIVE' "
            + "GROUP BY e.classId")
    List<ClassCount> countActiveGroupedByClassIds(@Param("classIds") Collection<Long> classIds);

    /**
     * Returns ACTIVE (classId, userId) pairs for the given classes. Used by the
     * teaching dashboard to compute per-class averages without N+1. Callers MUST
     * pass a non-empty collection.
     */
    @Query("SELECT e.classId AS classId, e.user.id AS userId FROM Enrollment e "
            + "WHERE e.classId IN :classIds AND e.status = 'ACTIVE'")
    List<ClassUserId> findActiveUserIdsByClassIds(@Param("classIds") Collection<Long> classIds);

    /** Projection for {@link #countActiveGroupedByClassIds}. */
    interface ClassCount {
        Long getClassId();
        Long getCnt();
    }

    /** Projection for {@link #findActiveUserIdsByClassIds}. */
    interface ClassUserId {
        Long getClassId();
        Long getUserId();
    }

    /**
     * Returns the (at-most-one) enrollment row for the given
     * (user, class) pair regardless of status. Required by the join
     * pipeline to distinguish first-time joins, re-joins after
     * REMOVED, already-ACTIVE short-circuits, and COMPLETED
     * rejections.
     *
     * <p>The unique index {@code idx_enroll_user_class} guarantees at
     * most one row per pair.
     */
    @Query("SELECT e FROM Enrollment e WHERE e.user.id = :userId AND e.classId = :classId")
    Optional<Enrollment> findByUserIdAndClassId(@Param("userId") Long userId,
                                                 @Param("classId") Long classId);

    /**
     * Returns all enrollments for the given user with the specified
     * status, ordered by {@code joined_at} descending. Used to power
     * {@code GET /my/classes}.
     *
     * <p>Note: callers must filter the resulting class list
     * against {@code classes.is_deleted = 0} themselves — this
     * repository does not join {@code classes}.
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.user " +
            "WHERE e.user.id = :userId AND e.status = :status " +
            "ORDER BY e.joinedAt DESC")
    List<Enrollment> findAllByUserIdAndStatusOrderByJoinedAtDesc(@Param("userId") Long userId,
                                                                  @Param("status") String status);

    /**
     * Returns the number of currently ACTIVE enrollments for the
     * given class. Capacity check uses this against
     * {@code classes.max_students}.
     */
    @Query("SELECT COUNT(e) FROM Enrollment e WHERE e.classId = :classId AND e.status = 'ACTIVE'")
    long countActiveByClassId(@Param("classId") Long classId);

    /**
     * Returns the distinct class ids the given user is ACTIVE-enrolled in. Used
     * by messaging's recipient gate to resolve a student's set of classes before
     * mapping them to the lecturers that teach them.
     *
     * @param userId the student's user id
     * @return distinct ACTIVE class ids for that user
     */
    @Query("SELECT DISTINCT e.classId FROM Enrollment e " +
            "WHERE e.user.id = :userId AND e.status = 'ACTIVE'")
    List<Long> findActiveClassIdsForUser(@Param("userId") Long userId);

    /**
     * Returns the distinct users ACTIVE-enrolled in any of the given classes,
     * optionally filtered by a case-insensitive name/email substring. Used by
     * messaging's recipient gate (lecturer → eligible students) and recipient
     * search. Returns empty when {@code classIds} is empty.
     *
     * @param classIds the classes to gather students from
     * @param q        optional case-insensitive name/email filter; null/blank disables
     * @return distinct ACTIVE-enrolled users across those classes
     */
    @Query("SELECT DISTINCT e.user FROM Enrollment e " +
            "WHERE e.classId IN :classIds AND e.status = 'ACTIVE' " +
            "AND (:q IS NULL OR :q = '' " +
            "     OR LOWER(e.user.fullName) LIKE LOWER(CONCAT('%', :q, '%')) " +
            "     OR LOWER(e.user.email) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<User> findActiveStudentsInClasses(@Param("classIds") Collection<Long> classIds,
                                           @Param("q") String q);
}
