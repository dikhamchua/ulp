package com.ulp.features.tests.repository;

import com.ulp.entities.TestActivity;
import com.ulp.features.tests.dto.TestActivityRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link TestActivity}.
 *
 * <p>Append-only by convention — callers only ever insert via
 * {@link JpaRepository#save(Object)}. Mirrors {@code UserActivityRepository}
 * from the admin user-management feature.
 */
public interface TestActivityRepository extends JpaRepository<TestActivity, Long> {

    /**
     * Paged audit history for a single exam, projected into
     * {@link TestActivityRow} so the template renders without per-row N+1
     * lookups. The LEFT JOIN against {@code User} resolves the actor's email
     * in the same query; rows whose actor has been hard-deleted surface with
     * {@code actorEmail = null}.
     *
     * @param testId   the exam the audit rows are about
     * @param pageable paging directives — {@code Sort} is ignored because the
     *                 query hard-codes {@code ORDER BY a.createdAt DESC}
     * @return one page of {@link TestActivityRow}, newest first
     */
    @Query("SELECT new com.ulp.features.tests.dto.TestActivityRow(" +
           "  a.id, a.type, a.description, u.email, a.createdAt) " +
           "FROM TestActivity a " +
           "LEFT JOIN User u ON u.id = a.createdBy " +
           "WHERE a.testId = :testId " +
           "ORDER BY a.createdAt DESC, a.id DESC")
    Page<TestActivityRow> findActivitiesForTest(@Param("testId") Long testId,
                                                Pageable pageable);
}
