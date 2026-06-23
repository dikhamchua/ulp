package com.ulp.classes.repository;

import com.ulp.classes.entity.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * @param classId the ID of the class
     * @param status  the enrollment status to filter by (e.g. {@code "ACTIVE"})
     * @return list of matching {@link Enrollment} records
     */
    List<Enrollment> findAllByClassIdAndStatusOrderByJoinedAtDesc(Long classId, String status);

    /**
     * Counts the number of enrollments for a given class with the specified status.
     *
     * @param classId the ID of the class
     * @param status  the enrollment status to filter by (e.g. {@code "ACTIVE"})
     * @return the count of matching enrollments
     */
    long countByClassIdAndStatus(Long classId, String status);
}
