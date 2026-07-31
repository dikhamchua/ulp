package com.ulp.features.admin.settings.service;

import com.ulp.entities.AiProvider;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.AiProviderForm;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.AiProviderRow;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.TestResult;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.log.AiRequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.ulp.features.admin.settings.dto.AiSettingsDtos.MASKED;

/**
 * Business logic behind the {@code /admin/settings/ai} screen: listing providers,
 * creating and updating them, toggling, deleting, and running the connection test.
 *
 * <p><b>Key handling.</b> {@link #listRows()} never returns the stored key — every row
 * carries a fixed dot run instead, so the rendered HTML contains no fragment of the real
 * value. {@link #revealKey(Long)} is the single path that returns the plain key, and it
 * is reached only through an authenticated, permission-gated JSON endpoint.
 *
 * <p><b>Edit semantics.</b> A blank {@code apiKey} or the sentinel {@code MASKED} means
 * "keep the stored key" — the same convention {@code EmailSettingsService} uses for the
 * SMTP password. On create, a key is mandatory.
 */
@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    /** Fixed dot run rendered in the table's API key column, independent of real key length. */
    private static final String MASKED_DISPLAY = "••••••••••••";

    private static final String PING_MESSAGE = "ping";

    /**
     * Token budget for the connectivity ping, sized for reasoning models.
     *
     * <p>A reasoning model spends tokens on an internal chain of thought before emitting
     * the first character of its reply, so a small budget is exhausted mid-thought: the
     * provider returns {@code finish_reason: "length"} and the test fails even though it
     * is reachable and authenticated. Measured cost of answering the word "ping" varies
     * by two orders of magnitude across providers, so this ceiling is set well above the
     * observed worst case rather than fitted to it — a connectivity check is a manual,
     * infrequent admin action costing a fraction of a cent, and a budget guessed too low
     * fails a healthy provider, which is the far more expensive mistake.
     */
    private static final int PING_MAX_TOKENS = 2048;

    private final AiProviderRepository repository;
    private final AiClient aiClient;

    public AiProviderService(AiProviderRepository repository, AiClient aiClient) {
        this.repository = repository;
        this.aiClient = aiClient;
    }

    /**
     * Loads every provider in fallback order as view rows.
     *
     * <p>{@code index} is the 1-based position in the list, not the stored
     * {@code display_order} — deleting a middle provider leaves the stored orders with a
     * gap on purpose, and the table still numbers rows contiguously.
     *
     * @return provider rows ordered by display order ascending; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<AiProviderRow> listRows() {
        List<AiProvider> providers = repository.findAllOrdered();
        List<AiProviderRow> rows = new ArrayList<>(providers.size());
        int index = 1;
        for (AiProvider p : providers) {
            rows.add(new AiProviderRow(p.getId(), index++, p.getName(), p.getModel(),
                    p.getBaseUrl(), MASKED_DISPLAY, p.isEnabled()));
            }
        return rows;
    }

    /** Returns true when at least one provider exists but none of them is enabled. */
    @Transactional(readOnly = true)
    public boolean hasProvidersButAllDisabled() {
        return repository.count() > 0 && repository.findEnabledOrdered().isEmpty();
    }

    /**
     * Loads a provider as an edit form. The {@code apiKey} field is filled with the
     * masked sentinel so submitting the form untouched keeps the stored key.
     *
     * @param id provider identifier
     * @return the populated form, or empty when no such provider exists
     */
    @Transactional(readOnly = true)
    public Optional<AiProviderForm> loadForm(Long id) {
        return repository.findById(id).map(p -> new AiProviderForm(
                p.getId(), p.getName(), p.getBaseUrl(), p.getModel(), MASKED, p.isEnabled()));
    }

    /**
     * Loads the live provider entity for the detail header (name, status, timestamps).
     *
     * @param id provider identifier
     * @return the entity, or empty when no such provider exists
     */
    @Transactional(readOnly = true)
    public Optional<AiProvider> findById(Long id) {
        return repository.findById(id);
    }

    /**
     * Returns the plain API key of one provider. This is the only method that exposes the
     * stored secret, and it exists solely to back the eye and copy buttons.
     *
     * @param id provider identifier
     * @return the stored key, or empty when no such provider exists
     */
    @Transactional(readOnly = true)
    public Optional<String> revealKey(Long id) {
        return repository.findById(id).map(AiProvider::getApiKey);
    }

    /**
     * Reports whether another provider already uses this name, so the controller can
     * surface a duplicate as an inline field error before the unique key fires.
     *
     * @param name      the candidate name
     * @param excludeId provider being edited, excluded from the check; may be {@code null}
     * @return {@code true} when the name is taken by a different row
     */
    @Transactional(readOnly = true)
    public boolean isNameTaken(String name, Long excludeId) {
        return repository.findByName(name == null ? "" : name.trim())
                .filter(existing -> !existing.getId().equals(excludeId))
                .isPresent();
    }

    /**
     * Creates a provider, appending it to the end of the fallback chain.
     *
     * <p>{@code display_order} is assigned as {@code max + 1}. Because deletes never
     * renumber, that maximum only ever grows, so a freed slot in the middle stays free
     * and no collision is possible; the unique key is the final guard.
     *
     * @param form          submitted values, already validated
     * @param currentUserId admin performing the change, stamped on {@code updated_by}
     */
    @Transactional
    public void create(AiProviderForm form, Long currentUserId) {
        AiProvider provider = new AiProvider(
                form.name().trim(),
                AiClient.normalizeBaseUrl(form.baseUrl()),
                form.model().trim(),
                form.apiKey().trim());
        provider.setEnabled(form.enabled());
        provider.setDisplayOrder(nextDisplayOrder());
        provider.setUpdatedBy(currentUserId);
        repository.save(provider);
        log.info("AI provider '{}' created by user {}", provider.getName(), currentUserId);
    }

    /**
     * Updates an existing provider.
     *
     * <p>The API key is rewritten only when the form carries a real new value — a blank
     * field or the {@code MASKED} sentinel leaves the stored key untouched.
     *
     * @param form          submitted values including the target {@code id}
     * @param currentUserId admin performing the change
     * @return {@code true} when a row was updated, {@code false} when the id was unknown
     */
    @Transactional
    public boolean update(AiProviderForm form, Long currentUserId) {
        Optional<AiProvider> found = repository.findById(form.id());
        if (found.isEmpty()) {
            return false;
        }
        AiProvider provider = found.get();
        provider.setName(form.name().trim());
        provider.setBaseUrl(AiClient.normalizeBaseUrl(form.baseUrl()));
        provider.setModel(form.model().trim());
        provider.setEnabled(form.enabled());
        if (hasNewKey(form.apiKey())) {
            provider.setApiKey(form.apiKey().trim());
        }
        provider.setUpdatedBy(currentUserId);
        repository.save(provider);
        log.info("AI provider '{}' updated by user {} ({})", provider.getName(), currentUserId,
                hasNewKey(form.apiKey()) ? "key replaced" : "key unchanged");
        return true;
    }

    /**
     * Flips the enabled flag. A disabled provider is skipped entirely by {@code AiClient}.
     *
     * @param id            provider identifier
     * @param currentUserId admin performing the change
     * @return the new enabled state, or empty when the id was unknown
     */
    @Transactional
    public Optional<Boolean> toggleEnabled(Long id, Long currentUserId) {
        return repository.findById(id).map(provider -> {
            provider.setEnabled(!provider.isEnabled());
            provider.setUpdatedBy(currentUserId);
            repository.save(provider);
            return provider.isEnabled();
        });
    }

    /**
     * Hard-deletes a provider. Surviving rows keep their {@code display_order}, leaving a
     * gap — the freed slot stays available because the column is nullable and new rows
     * always append at {@code max + 1}.
     *
     * @param id provider identifier
     * @return {@code true} when a row was removed
     */
    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        log.info("AI provider {} deleted", id);
        return true;
    }

    /**
     * Sends one tiny chat message to a single provider to verify it answers.
     *
     * <p>Not {@code @Transactional} for the network call itself — only the lookup touches
     * the database. Any failure is converted into a {@link TestResult} the UI toasts,
     * rather than propagating as a 500.
     *
     * <p>The attempt is recorded in {@code ai_request_logs} under the
     * {@code TEST_CONNECTION} source by {@code AiClient}, attributed to {@code actorId}.
     *
     * @param id      provider identifier
     * @param actorId the admin who pressed the test button, or {@code null} when unknown
     * @return the outcome, with a human-readable error on failure
     */
    public TestResult test(Long id, Long actorId) {
        Optional<AiProvider> found = repository.findById(id);
        if (found.isEmpty()) {
            return new TestResult(false, "Không tìm thấy provider");
        }
        try {
            aiClient.callOne(found.get(), PING_MESSAGE, PING_MAX_TOKENS,
                    AiRequestLogger.SOURCE_TEST_CONNECTION, actorId);
            return new TestResult(true, null);
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("AI provider test failed for id {}: {}", id, detail);
            return new TestResult(false, detail);
        }
    }

    // ─────────────────────────────────────────────────────────────────

    /** A blank field or the masked sentinel both mean "keep the stored key". */
    private static boolean hasNewKey(String submitted) {
        return submitted != null && !submitted.isBlank() && !MASKED.equals(submitted.trim());
    }

    private Short nextDisplayOrder() {
        return repository.findMaxDisplayOrder()
                .map(max -> (short) (max + 1))
                .orElse((short) 1);
    }
}
