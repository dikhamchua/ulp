package com.ulp.features.subjects.repository;

import com.ulp.features.subjects.entity.SubjectChapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for subject sample-chapter outline rows.
 *
 * <p>{@link SubjectChapter} filters soft-deleted rows via {@code @SQLRestriction}.
 */
public interface SubjectChapterRepository extends JpaRepository<SubjectChapter, Long> {

    /** Live chapters of a subject in authored order. */
    List<SubjectChapter> findBySubjectIdOrderByDisplayOrderAsc(Long subjectId);

    /** Loads a chapter scoped to its parent subject. */
    Optional<SubjectChapter> findByIdAndSubjectId(Long id, Long subjectId);

    /**
     * Highest live {@code display_order} for the subject, or {@code -1} when empty.
     * Soft-deleted rows are excluded so freed slots can be reused.
     */
    @Query(value = "SELECT COALESCE(MAX(display_order), -1) FROM subject_chapters "
            + "WHERE subject_id = :subjectId AND is_deleted = 0",
            nativeQuery = true)
    short findMaxDisplayOrder(@Param("subjectId") Long subjectId);
}
