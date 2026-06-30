package com.ulp.features.lessons.repository;

import com.ulp.entities.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

