package com.ulp.features.lessons.repository;

import com.ulp.entities.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Lesson}.
 *
 * <p>{@link Lesson} carries a {@code @SQLRestriction("is_deleted = 0")} so
 * every method below transparently filters out soft-deleted rows.
 */
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    /** Returns the live lessons of a section ordered by {@code display_order}. */
    List<Lesson> findBySectionIdOrderByDisplayOrderAsc(Long sectionId);

    /**
     * Returns a section's live lessons in the given status, ordered by
     * {@code display_order}. Single source for the "PUBLISHED lessons of a
     * section" projection shared by the student lesson list and the lecturer
     * drill-down; {@code @SQLRestriction} still filters soft-deleted rows.
     */
    List<Lesson> findBySectionIdAndStatusOrderByDisplayOrderAsc(Long sectionId, String status);

    /**
     * Returns the ids of every PUBLISHED, non-soft-deleted lesson belonging to
     * a class (via its sections). Used by the lecturer progress dashboard as the
     * denominator + the scoped id-list for the single grouped aggregate query,
     * avoiding a per-student query (ULP lecturer-student-progress, design D2).
     *
     * <p>The {@code @SQLRestriction} on both {@link Lesson} and {@code Section}
     * transparently excludes soft-deleted rows; the status filter keeps DRAFT
     * lessons out of the denominator.
     */
    @Query("SELECT l.id FROM Lesson l WHERE l.status = 'PUBLISHED' AND l.sectionId IN "
            + "(SELECT s.id FROM Section s WHERE s.classId = :classId)")
    List<Long> findPublishedLessonIdsByClassId(@Param("classId") Long classId);

    /**
     * Batch variant of {@link #findPublishedLessonIdsByClassId}: returns
     * (classId, lessonId) pairs for every PUBLISHED lesson across the given
     * classes. Soft-deleted lessons/sections are filtered by
     * {@code @SQLRestriction}. Callers MUST pass a non-empty collection.
     */
    @Query("SELECT s.classId AS classId, l.id AS lessonId FROM Lesson l, Section s "
            + "WHERE l.sectionId = s.id AND l.status = 'PUBLISHED' "
            + "AND s.classId IN :classIds")
    List<ClassLessonId> findPublishedLessonIdsByClassIds(
            @Param("classIds") Collection<Long> classIds);

    /** Projection for {@link #findPublishedLessonIdsByClassIds}. */
    interface ClassLessonId {
        Long getClassId();
        Long getLessonId();
    }

    /** Loads a lesson scoped by section to harden the URL hierarchy. */
    Optional<Lesson> findByIdAndSectionId(Long id, Long sectionId);

    /**
     * Returns the highest {@code display_order} currently used for the given
     * section, or {@code -1} when the section has no lessons yet. The native
     * query bypasses {@code @SQLRestriction} so soft-deleted rows are not
     * considered — once a position is freed it can be re-used without a
     * numbering conflict.
     */
    @Query(value = "SELECT COALESCE(MAX(display_order), -1) FROM lessons "
            + "WHERE section_id = :sectionId AND is_deleted = 0",
           nativeQuery = true)
    short findMaxDisplayOrder(@Param("sectionId") Long sectionId);

    /**
     * Clears {@code lessons.pdf_attachment_id} for every row that points at
     * the supplied attachment id. Called before the attachment row itself is
     * deleted so no dangling FK exists during the cascade — see design D2.
     */
    @Modifying
    @Query(value = "UPDATE lessons SET pdf_attachment_id = NULL "
            + "WHERE pdf_attachment_id = :attachmentId",
           nativeQuery = true)
    void clearPdfAttachmentId(@Param("attachmentId") Long attachmentId);
}

