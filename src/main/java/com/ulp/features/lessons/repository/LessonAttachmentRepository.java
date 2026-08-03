package com.ulp.features.lessons.repository;

import com.ulp.entities.LessonAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * Batch count of lesson attachments whose parent lesson belongs to a section
     * of the given classes. Soft-deleted lessons/sections are excluded via
     * {@code @SQLRestriction} on the joined entities. Callers MUST pass a
     * non-empty collection.
     */
    @Query("SELECT s.classId AS classId, COUNT(a) AS cnt "
            + "FROM LessonAttachment a, Lesson l, Section s "
            + "WHERE a.lessonId = l.id AND l.sectionId = s.id "
            + "AND s.classId IN :classIds "
            + "GROUP BY s.classId")
    List<ClassCount> countGroupedByClassIds(@Param("classIds") Collection<Long> classIds);

    /** Projection for grouped class counts ({@code classId}, {@code cnt}). */
    interface ClassCount {
        Long getClassId();
        Long getCnt();
    }
}
