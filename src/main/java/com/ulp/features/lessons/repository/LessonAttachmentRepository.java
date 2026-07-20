package com.ulp.features.lessons.repository;

import com.ulp.entities.LessonAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link LessonAttachment}.
 *
 * <p>Attachments are hard-deleted (no {@code is_deleted} flag), so all
 * queries operate on the full table without a {@code @SQLRestriction} filter.
 */
public interface LessonAttachmentRepository extends JpaRepository<LessonAttachment, Long> {

    /** Lists attachments of a lesson ordered by upload time (oldest first). */
    List<LessonAttachment> findByLessonIdOrderByUploadedAtAsc(Long lessonId);

    /** Loads an attachment scoped by lesson to harden the URL hierarchy. */
    Optional<LessonAttachment> findByIdAndLessonId(Long id, Long lessonId);

    /**
     * Bulk-deletes every attachment row for the given lesson. The on-disk
     * files must be removed separately by the caller — see
     * {@code LessonAttachmentsService.deleteAllByLesson}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM LessonAttachment a WHERE a.lessonId = :lessonId")
    int deleteByLessonId(@Param("lessonId") Long lessonId);
}
