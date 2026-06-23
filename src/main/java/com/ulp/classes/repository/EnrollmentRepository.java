package com.ulp.classes.repository;

import com.ulp.classes.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for {@link Enrollment} entities.
 *
 * <p>Provides read-oriented queries needed by Sprint 2 to render the member
 * list on the class detail page.
 */
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    /**
     * Returns all enrollments for a given class with the specified status,
     * ordered by {@code joined_at} descending (most recent first).
     *
     * <p>Uses {@code JOIN FETCH} to eagerly load the associated {@link com.ulp.auth.entity.User}
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
}
