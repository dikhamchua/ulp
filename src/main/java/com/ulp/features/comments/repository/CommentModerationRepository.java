package com.ulp.features.comments.repository;

import com.ulp.entities.CommentModeration;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CommentModeration} audit rows
 * (comment hide/unhide history, ULP-11.7). Insert-only in this change.
 */
public interface CommentModerationRepository extends JpaRepository<CommentModeration, Long> {
}
