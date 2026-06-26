package com.ulp.features.lessons.repository;

import com.ulp.entities.SectionActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link SectionActivity}.
 *
 * <p>Audit rows are append-only — no update / delete methods are exposed.
 * Reads are paginated and always scoped by {@code sectionId} so the history
 * tab can render one section's audit trail efficiently even when the table
 * grows large across the whole platform.
 */
public interface SectionActivityRepository extends JpaRepository<SectionActivity, Long> {

    /**
     * Returns audit rows for a single section, newest first. The
     * {@code Pageable} caller decides the page size and offset; the index
     * {@code idx_asec_section (section_id, created_at)} from V3 keeps the
     * query covered.
     */
    Page<SectionActivity> findBySectionIdOrderByCreatedAtDesc(Long sectionId,
                                                              Pageable pageable);
}
