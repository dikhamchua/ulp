package com.ulp.features.comments.repository;

import com.ulp.entities.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Comment} (ULP-4.6).
 *
 * <p>{@link Comment} deliberately has NO {@code @SQLRestriction}, so the reply
 * query below still returns soft-deleted rows — a deleted mid-thread node must
 * be visible so it can render as a placeholder anchoring its live children. The
 * ROOT page query, by contrast, excludes deleted roots on purpose (see below).
 */
public interface LessonCommentRepository extends JpaRepository<Comment, Long> {

    /**
     * Returns one page of APPROVED, NON-deleted ROOT comments (parent_id IS NULL)
     * of a lesson. Deleted roots are excluded from both the page and its total
     * count so an all-deleted thread reads as empty (no phantom "load more" and a
     * correct empty state); their orphaned replies are dropped with them. Ordering
     * is governed by the {@link Pageable}'s sort (newest-first in the service).
     */
    Page<Comment> findByLessonIdAndParentIdIsNullAndDeletedFalseAndModerationStatus(
            Long lessonId, String moderationStatus, Pageable pageable);

    /**
     * Batch-loads APPROVED replies for a set of parent ids (used to fetch the
     * level-2 and level-3 layers of a paged root window). Callers MUST guard
     * against an empty {@code parentIds} — MySQL {@code IN ()} is invalid SQL.
     */
    List<Comment> findByParentIdInAndModerationStatus(Collection<Long> parentIds,
                                                      String moderationStatus);

    /**
     * Moderator variant of the ROOT page query (ULP-11.7): loads non-deleted
     * roots whose {@code moderation_status} is in the given set, so a moderator
     * page includes hidden (REJECTED) roots alongside APPROVED ones. The student
     * path keeps calling the single-status method above.
     */
    Page<Comment> findByLessonIdAndParentIdIsNullAndDeletedFalseAndModerationStatusIn(
            Long lessonId, Collection<String> moderationStatuses, Pageable pageable);

    /**
     * Moderator variant of the reply batch-load: returns replies whose status is
     * in the given set. Callers MUST guard against an empty {@code parentIds} —
     * MySQL {@code IN ()} is invalid SQL.
     */
    List<Comment> findByParentIdInAndModerationStatusIn(Collection<Long> parentIds,
                                                        Collection<String> moderationStatuses);
}
