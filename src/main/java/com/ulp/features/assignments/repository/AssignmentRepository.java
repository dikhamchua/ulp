package com.ulp.features.assignments.repository;

import com.ulp.features.assignments.entity.Assignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Assignment} entities.
 *
 * <p>All queries exclude soft-deleted rows ({@code is_deleted = 0}) because
 * Hibernate {@code validate} mode does not support {@code @Where} clause
 * reliably with MySQL — callers must use the {@code NotDeleted} variants.
 */
public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    /**
     * Returns all non-deleted assignments for a class, ordered newest first.
     * Used by the lecturer's assignment list.
     *
     * @param classId the class id
     * @return assignments in that class
     */
    @Query("SELECT a FROM Assignment a WHERE a.classId = :classId AND a.deleted = false ORDER BY a.createdAt DESC")
    List<Assignment> findAllByClassIdNotDeleted(@Param("classId") Long classId);

    /**
     * Finds a non-deleted assignment by its id and class id.
     * Scoped to the class to prevent cross-class enumeration.
     *
     * @param id      the assignment id
     * @param classId the expected class id
     * @return the assignment, or empty if absent or from a different class
     */
    @Query("SELECT a FROM Assignment a WHERE a.id = :id AND a.classId = :classId AND a.deleted = false")
    Optional<Assignment> findByIdAndClassIdNotDeleted(@Param("id") Long id, @Param("classId") Long classId);

    /**
     * Returns published (and closed) assignments visible to students.
     * Students only see PUBLISHED or CLOSED; DRAFT is hidden.
     *
     * @param classId the class id
     * @return non-draft, non-deleted assignments for that class
     */
    @Query("SELECT a FROM Assignment a WHERE a.classId = :classId AND a.deleted = false AND a.status <> 'DRAFT' ORDER BY a.createdAt DESC")
    List<Assignment> findPublishedByClassId(@Param("classId") Long classId);
}
