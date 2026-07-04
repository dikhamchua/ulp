package com.ulp.features.tests.repository;

import com.ulp.features.tests.entity.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Repository for {@link Test}. The entity's {@code @SQLRestriction("is_deleted
 * = 0")} already filters soft-deleted rows from every query below.
 */
public interface TestRepository extends JpaRepository<Test, Long> {

    /** Published exams for the given classes (student list / readiness pool). */
    List<Test> findByClassIdInAndStatusOrderByUpdatedAtDesc(Collection<Long> classIds, String status);

    /** Published exams for the given classes limited to the given types (readiness pool). */
    List<Test> findByClassIdInAndStatusAndTypeInOrderByUpdatedAtDesc(
            Collection<Long> classIds, String status, Collection<String> types);

    /** The caller's own tests of a given type (own PRACTICE tests). */
    List<Test> findByCreatedByAndTypeOrderByUpdatedAtDesc(Long createdBy, String type);

    /**
     * Exams the lecturer owns — created by them OR belonging to a class they
     * lead. {@code classIds} must be non-empty (callers pass a sentinel when the
     * lecturer leads no class) so the JPQL {@code IN} clause stays valid.
     */
    @Query("SELECT t FROM Test t WHERE t.createdBy = :userId OR t.classId IN :classIds")
    Page<Test> findOwnedByLecturer(@Param("userId") Long userId,
                                    @Param("classIds") Collection<Long> classIds,
                                    Pageable pageable);

    /** Exams belonging to a single class (class-detail "Bài test" tab). */
    Page<Test> findByClassId(Long classId, Pageable pageable);
}
