package com.ulp.features.library.repository;

import com.ulp.entities.LibraryAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data repository for {@link LibraryAsset}. Soft-deleted rows are
 * excluded by the entity {@code @SQLRestriction}.
 */
public interface LibraryAssetRepository extends JpaRepository<LibraryAsset, Long> {

    /** Owner-scoped lookup; returns empty for other owners or soft-deleted rows. */
    Optional<LibraryAsset> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * Lists the owner's assets with optional kind filter and case-insensitive
     * title/filename search. Blank {@code q} matches everything; blank
     * {@code kind} skips the kind filter.
     */
    @Query("""
            SELECT a FROM LibraryAsset a
            WHERE a.ownerId = :ownerId
              AND (:kind IS NULL OR :kind = '' OR a.kind = :kind)
              AND (
                    :q IS NULL OR :q = ''
                    OR LOWER(a.title) LIKE LOWER(CONCAT('%', :q, '%'))
                    OR LOWER(a.originalFilename) LIKE LOWER(CONCAT('%', :q, '%'))
                  )
            ORDER BY a.updatedAt DESC
            """)
    Page<LibraryAsset> searchOwned(@Param("ownerId") Long ownerId,
                                   @Param("q") String q,
                                   @Param("kind") String kind,
                                   Pageable pageable);

    /** Total non-deleted assets owned by the user (sidebar "All" badge). */
    long countByOwnerId(Long ownerId);

    /** Kind-scoped count for sidebar DOCUMENT / VIDEO badges. */
    long countByOwnerIdAndKind(Long ownerId, String kind);

    /** Counts attachment rows that still reference the asset. */
    @Query(value = """
            SELECT COUNT(*) FROM lesson_attachments
            WHERE library_asset_id = :assetId
            """, nativeQuery = true)
    long countAttachmentReferences(@Param("assetId") Long assetId);

    /**
     * Counts non-deleted lessons that still reference the asset as uploaded
     * video. Soft-deleted lessons should have cleared the FK already.
     */
    @Query(value = """
            SELECT COUNT(*) FROM lessons
            WHERE video_library_asset_id = :assetId
              AND is_deleted = 0
            """, nativeQuery = true)
    long countLessonVideoReferences(@Param("assetId") Long assetId);
}
