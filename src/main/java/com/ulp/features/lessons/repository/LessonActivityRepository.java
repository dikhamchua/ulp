package com.ulp.features.lessons.repository;

import com.ulp.entities.LessonActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link LessonActivity}.
 *
 * <p>Audit rows are append-only — no update / delete methods are exposed.
 * Reads are paginated and always scoped by {@code lessonId} so the history
 * tab can render one lesson's audit trail efficiently even when the table
 * grows large across the whole platform.
 */
public interface LessonActivityRepository extends JpaRepository<LessonActivity, Long> {

    /**
     * Returns audit rows for a single lesson, newest first. The
     * {@code Pageable} caller decides the page size and offset; the index
     * {@code idx_al_lesson (lesson_id, created_at)} from V3 keeps the
     * query covered.
     */
    Page<LessonActivity> findByLessonIdOrderByCreatedAtDesc(Long lessonId,
                                                             Pageable pageable);
}
