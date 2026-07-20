package com.ulp.features.classes.repository;

import com.ulp.entities.ClassEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ClassEntity}.
 *
 * <p>Because the entity is annotated with {@code @SQLRestriction("is_deleted = 0")},
 * every query issued through this repository automatically excludes soft-deleted
 * records without any additional filter in the calling code.
 */
public interface ClassRepository extends JpaRepository<ClassEntity, Long> {

    List<ClassEntity> findAllByLecturerIdOrderByCreatedAtDesc(Long lecturerId);

    List<ClassEntity> findAllByOrderByCreatedAtDesc();

    Optional<ClassEntity> findByCode(String code);

    /**
     * Returns the (non-deleted) classes owned by the supplied lecturer.
     *
     * <p>The {@code @SQLRestriction("is_deleted = 0")} on {@link ClassEntity}
     * already filters out soft-deleted rows, so this method surfaces ONLY the
     * live ownership relationships — used by the admin role-demote warning
     * to list the classes that would be left without a teaching lecturer.
     */
    List<ClassEntity> findAllByLecturerId(Long lecturerId);

    /**
     * Paginated variant of {@link #findAllByLecturerId(Long)} used by the
     * lecturer class list to avoid loading the entire owned-class set into
     * memory.
     *
     * <p>Soft-deleted rows are filtered automatically by the entity's
     * {@code @SQLRestriction}. Sort direction is supplied by the caller via
     * {@link Pageable}; controllers typically pass
     * {@code Sort.by(Direction.DESC, "createdAt")}.
     */
    Page<ClassEntity> findAllByLecturerId(Long lecturerId, Pageable pageable);

    /**
     * Paginated variant of the all-non-deleted query used by HEAD / ADMIN
     * viewing the lecturer class list. The {@code @SQLRestriction} on
     * {@link ClassEntity} keeps soft-deleted rows out of the result.
     *
     * <p>The derived-query name {@code findAllBy} resolves to "select all
     * entities" because there are no property predicates after the
     * {@code By}; Spring Data interprets this as the no-arg find-all that
     * accepts a {@link Pageable}, equivalent to {@code findAll(Pageable)} but
     * with an explicit method on the repository for clarity.
     */
    Page<ClassEntity> findAllBy(Pageable pageable);

    /**
     * Returns the distinct lecturer ids that teach any of the given classes.
     * Used by messaging's recipient gate to map a student's ACTIVE-enrollment
     * classes to the lecturers eligible to be messaged. Soft-deleted classes are
     * excluded by the entity's {@code @SQLRestriction}. Returns empty when
     * {@code classIds} is empty.
     *
     * @param classIds the classes to resolve lecturers from
     * @return distinct lecturer ids teaching those classes
     */
    @Query("SELECT DISTINCT c.lecturerId FROM ClassEntity c WHERE c.id IN :classIds")
    List<Long> findLecturerIdsForClasses(@Param("classIds") Collection<Long> classIds);

    /**
     * Returns the distinct ids of (non-deleted) classes taught by the given
     * lecturer. Used by messaging's recipient gate (lecturer → the students in
     * their classes).
     *
     * @param lecturerId the lecturer's user id
     * @return distinct class ids taught by that lecturer
     */
    @Query("SELECT c.id FROM ClassEntity c WHERE c.lecturerId = :lecturerId")
    List<Long> findClassIdsForLecturer(@Param("lecturerId") Long lecturerId);

    /** Non-deleted classes owned by a department, newest first. */
    List<ClassEntity> findAllByDepartmentIdOrderByCreatedAtDesc(Long departmentId);

    /** Paginated department-scoped class list. */
    Page<ClassEntity> findAllByDepartmentId(Long departmentId, Pageable pageable);

    long countByDepartmentId(Long departmentId);
}
