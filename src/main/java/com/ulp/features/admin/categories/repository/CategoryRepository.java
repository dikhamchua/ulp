package com.ulp.features.admin.categories.repository;

import com.ulp.entities.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data repository for {@link Category}. Provides the queries the
 * service needs to build the two-level tree, enforce slug uniqueness, and run
 * the delete guards (child count + course-link count).
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** All top-level categories (parent_id IS NULL), ordered by name. */
    List<Category> findByParentIdIsNullOrderByNameAsc();

    /** All children of the given parent, ordered by name. */
    List<Category> findByParentIdOrderByNameAsc(Long parentId);

    /** True when the given category has at least one child (delete guard + demote guard). */
    boolean existsByParentId(Long parentId);

    /** Slug uniqueness check on create. */
    boolean existsBySlug(String slug);

    /** Slug uniqueness check on edit, excluding the row being edited. */
    boolean existsBySlugAndIdNot(String slug, Long id);

    /**
     * Counts how many courses reference the given category via
     * {@code course_categories}. A native query is used because there is no
     * JPA entity mapping the join table (it is read-only here — only counted
     * to block deletion of an in-use category).
     */
    @Query(value = "SELECT COUNT(*) FROM course_categories WHERE category_id = :categoryId",
            nativeQuery = true)
    long countCourseLinks(@Param("categoryId") Long categoryId);
}
