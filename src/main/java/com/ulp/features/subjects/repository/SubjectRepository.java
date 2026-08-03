package com.ulp.features.subjects.repository;

import com.ulp.features.subjects.entity.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Subject}.
 *
 * <p>Default queries already exclude soft-deleted rows via the entity
 * {@code @SQLRestriction}.
 */
public interface SubjectRepository extends JpaRepository<Subject, Long> {

    List<Subject> findAllByDepartmentIdOrderByCodeAsc(Long departmentId);

    List<Subject> findAllByOrderByCodeAsc();

    List<Subject> findAllByActiveTrueOrderByCodeAsc();

    boolean existsByDepartmentIdAndCodeIgnoreCase(Long departmentId, String code);

    boolean existsByDepartmentIdAndCodeIgnoreCaseAndIdNot(Long departmentId, String code, Long id);

    Optional<Subject> findByIdAndActiveTrue(Long id);

    @Query("""
            select s from Subject s
            where s.id = :id and s.active = true
            """)
    Optional<Subject> findActiveById(@Param("id") Long id);
}
