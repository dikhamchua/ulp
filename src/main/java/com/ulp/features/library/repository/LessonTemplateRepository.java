package com.ulp.features.library.repository;

import com.ulp.entities.LessonTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Owner-scoped repository for {@link LessonTemplate}. Soft-deleted rows are
 * excluded by the entity {@code @SQLRestriction}.
 */
public interface LessonTemplateRepository extends JpaRepository<LessonTemplate, Long> {

    Optional<LessonTemplate> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    /**
     * Lists the owner's templates with optional case-insensitive title search.
     */
    @Query("""
            SELECT t FROM LessonTemplate t
            WHERE t.ownerId = :ownerId
              AND (
                    :q IS NULL OR :q = ''
                    OR LOWER(t.title) LIKE LOWER(CONCAT('%', :q, '%'))
                  )
            ORDER BY t.updatedAt DESC
            """)
    Page<LessonTemplate> searchOwned(@Param("ownerId") Long ownerId,
                                     @Param("q") String q,
                                     Pageable pageable);

    /** Live templates that still pin the asset as main PDF. */
    @Query(value = """
            SELECT COUNT(*) FROM lesson_templates
            WHERE pdf_library_asset_id = :assetId
              AND is_deleted = 0
            """, nativeQuery = true)
    long countPdfAssetReferences(@Param("assetId") Long assetId);

    /** Live templates that still pin the asset as uploaded video. */
    @Query(value = """
            SELECT COUNT(*) FROM lesson_templates
            WHERE video_library_asset_id = :assetId
              AND is_deleted = 0
            """, nativeQuery = true)
    long countVideoAssetReferences(@Param("assetId") Long assetId);
}
