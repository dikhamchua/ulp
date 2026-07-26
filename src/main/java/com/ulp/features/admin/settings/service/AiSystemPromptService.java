package com.ulp.features.admin.settings.service;

import com.ulp.entities.AiSystemPrompt;
import com.ulp.features.admin.settings.dto.AiSystemPromptDtos.AiSystemPromptForm;
import com.ulp.features.admin.settings.dto.AiSystemPromptDtos.AiSystemPromptRow;
import com.ulp.features.admin.settings.repository.AiSystemPromptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Business logic behind the {@code /admin/settings/ai/prompts} screen: listing the
 * catalog, creating and updating prompts, toggling and deleting them.
 *
 * <p>Nothing here is secret, so — unlike {@code AiProviderService} — the read model
 * carries real content. It is shortened to a preview only so the table stays readable;
 * the full body is served to the edit panel untouched.
 *
 * <p>This service owns the catalog only. Resolving a prompt into an actual AI call is
 * deliberately out of scope: {@code AiClient} is not touched by this feature.
 */
@Service
public class AiSystemPromptService {

    private static final Logger log = LoggerFactory.getLogger(AiSystemPromptService.class);

    /** Maximum characters of the prompt body shown in the catalog table. */
    private static final int PREVIEW_LENGTH = 80;

    private static final String ELLIPSIS = "…";

    private final AiSystemPromptRepository repository;

    public AiSystemPromptService(AiSystemPromptRepository repository) {
        this.repository = repository;
    }

    /**
     * Loads the whole catalog as view rows, sorted by name.
     *
     * <p>{@code index} is the 1-based position in the rendered list, so the table always
     * numbers contiguously regardless of which ids survive a delete.
     *
     * @return prompt rows ordered by name ascending; never {@code null}
     */
    @Transactional(readOnly = true)
    public List<AiSystemPromptRow> listRows() {
        List<AiSystemPrompt> prompts = repository.findAllByOrderByNameAsc();
        List<AiSystemPromptRow> rows = new ArrayList<>(prompts.size());
        int index = 1;
        for (AiSystemPrompt p : prompts) {
            rows.add(new AiSystemPromptRow(p.getId(), index++, p.getName(),
                    p.getDescription(), preview(p.getContent()), p.isEnabled()));
        }
        return rows;
    }

    /**
     * Loads a prompt as an edit form, carrying the full stored body.
     *
     * @param id prompt identifier
     * @return the populated form, or empty when no such prompt exists
     */
    @Transactional(readOnly = true)
    public Optional<AiSystemPromptForm> loadForm(Long id) {
        return repository.findById(id).map(p -> new AiSystemPromptForm(
                p.getId(), p.getName(), p.getDescription(), p.getContent(), p.isEnabled()));
    }

    /**
     * Reports whether another prompt already uses this name, so the controller can
     * surface a duplicate as an inline field error before the unique key fires.
     *
     * @param name      the candidate name
     * @param excludeId prompt being edited, excluded from the check; may be {@code null}
     * @return {@code true} when the name is taken by a different row
     */
    @Transactional(readOnly = true)
    public boolean isNameTaken(String name, Long excludeId) {
        return repository.findByName(name == null ? "" : name.trim())
                .filter(existing -> !existing.getId().equals(excludeId))
                .isPresent();
    }

    /**
     * Creates a prompt.
     *
     * @param form          submitted values, already validated
     * @param currentUserId admin performing the change, stamped on {@code updated_by}
     */
    @Transactional
    public void create(AiSystemPromptForm form, Long currentUserId) {
        AiSystemPrompt prompt = new AiSystemPrompt(
                form.name().trim(),
                trimToNull(form.description()),
                form.content().trim());
        prompt.setEnabled(form.enabled());
        prompt.setUpdatedBy(currentUserId);
        repository.save(prompt);
        log.info("AI system prompt '{}' created by user {}", prompt.getName(), currentUserId);
    }

    /**
     * Updates an existing prompt in place.
     *
     * @param form          submitted values including the target {@code id}
     * @param currentUserId admin performing the change
     * @return {@code true} when a row was updated, {@code false} when the id was unknown
     */
    @Transactional
    public boolean update(AiSystemPromptForm form, Long currentUserId) {
        Optional<AiSystemPrompt> found = repository.findById(form.id());
        if (found.isEmpty()) {
            return false;
        }
        AiSystemPrompt prompt = found.get();
        prompt.setName(form.name().trim());
        prompt.setDescription(trimToNull(form.description()));
        prompt.setContent(form.content().trim());
        prompt.setEnabled(form.enabled());
        prompt.setUpdatedBy(currentUserId);
        repository.save(prompt);
        log.info("AI system prompt '{}' updated by user {}", prompt.getName(), currentUserId);
        return true;
    }

    /**
     * Flips the enabled flag. A disabled prompt stays in the catalog but is withheld
     * from the pickers that consume it.
     *
     * @param id            prompt identifier
     * @param currentUserId admin performing the change
     * @return the new enabled state, or empty when the id was unknown
     */
    @Transactional
    public Optional<Boolean> toggleEnabled(Long id, Long currentUserId) {
        return repository.findById(id).map(prompt -> {
            prompt.setEnabled(!prompt.isEnabled());
            prompt.setUpdatedBy(currentUserId);
            repository.save(prompt);
            return prompt.isEnabled();
        });
    }

    /**
     * Hard-deletes a prompt.
     *
     * @param id prompt identifier
     * @return {@code true} when a row was removed
     */
    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        log.info("AI system prompt {} deleted", id);
        return true;
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Collapses a prompt body to a single short line for the table cell.
     *
     * <p>Newlines become spaces first: a multi-line prompt would otherwise be cut at an
     * arbitrary point mid-paragraph and render as a blank-looking cell.
     */
    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_LENGTH
                ? flat
                : flat.substring(0, PREVIEW_LENGTH) + ELLIPSIS;
    }

    /** Description is optional; store {@code NULL} rather than an empty string. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
