package com.ulp.features.admin.settings.repository;

import com.ulp.entities.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code ai_providers} table.
 *
 * <p>Ordering is always by {@code display_order} ascending — both the admin settings
 * table and the {@code AiClient} fallback chain rely on that single ordering so the
 * order an admin sees is exactly the order calls are attempted in.
 */
@Repository
public interface AiProviderRepository extends JpaRepository<AiProvider, Long> {

    /**
     * Returns every provider, enabled or not, in fallback order.
     *
     * <p>Rows whose {@code display_order} is {@code NULL} sort last so a legacy or
     * partially-migrated row never silently jumps to the head of the chain.
     *
     * @return all providers ordered by display order ascending, nulls last
     */
    @Query("SELECT p FROM AiProvider p ORDER BY CASE WHEN p.displayOrder IS NULL THEN 1 ELSE 0 END, "
            + "p.displayOrder ASC, p.id ASC")
    List<AiProvider> findAllOrdered();

    /**
     * Returns only the enabled providers, in the same fallback order as
     * {@link #findAllOrdered()}. This is the exact list {@code AiClient} walks.
     *
     * @return enabled providers ordered by display order ascending, nulls last
     */
    @Query("SELECT p FROM AiProvider p WHERE p.enabled = true "
            + "ORDER BY CASE WHEN p.displayOrder IS NULL THEN 1 ELSE 0 END, "
            + "p.displayOrder ASC, p.id ASC")
    List<AiProvider> findEnabledOrdered();

    /**
     * Looks up a provider by its unique name — used to surface a duplicate name as an
     * inline field error before the database unique key rejects the insert.
     *
     * @param name the provider name to look for
     * @return the matching provider, or empty when the name is free
     */
    Optional<AiProvider> findByName(String name);

    /**
     * Returns the highest {@code display_order} currently in use, or empty when the
     * table holds no ordered row. Callers assign {@code max + 1} to a new provider.
     *
     * @return the current maximum display order, if any
     */
    @Query("SELECT MAX(p.displayOrder) FROM AiProvider p")
    Optional<Short> findMaxDisplayOrder();
}
