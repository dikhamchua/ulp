package com.ulp.features.classes.repository;

import com.ulp.entities.ClassEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * live ownership relationships â€” used by the admin role-demote warning
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
}
