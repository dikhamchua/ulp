package com.ulp.features.library.repository;

import com.ulp.entities.LessonTemplateAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repository for supplementary attachments on lesson templates.
 */
public interface LessonTemplateAttachmentRepository
        extends JpaRepository<LessonTemplateAttachment, Long> {

    List<LessonTemplateAttachment> findByTemplateIdOrderByDisplayOrderAsc(Long templateId);

    /** Counts template attachment rows that still reference the asset. */
    @Query(value = """
            SELECT COUNT(*) FROM lesson_template_attachments
            WHERE library_asset_id = :assetId
            """, nativeQuery = true)
    long countByLibraryAssetId(@Param("assetId") Long assetId);
}
