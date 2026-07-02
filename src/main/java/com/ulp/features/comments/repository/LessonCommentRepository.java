package com.ulp.features.comments.repository;

import com.ulp.entities.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Comment} (ULP-4.6).
 *
 * <p>{@link Comment} deliberately has NO {@code @SQLRestriction}, so these
 * methods return soft-deleted rows too — the list assembly in the service
 * needs to see a deleted root to render its placeholder. Callers filter
 * {@code is_deleted} in memory per the threading rules.
 */
public interface LessonCommentRepository extends JpaRepository<Comment, Long> {

    /**
     * Returns every APPROVED comment of a lesson oldest-first (roots and
     * replies interleaved by creation time). Deleted rows are included; the
     * service decides which to keep. Rejected / pending rows are excluded so
     * a future moderation sprint cannot leak them through this API.
     */
    List<Comment> findByLessonIdAndModerationStatusOrderByCreatedAtAsc(Long lessonId,
                                                                       String moderationStatus);
}
