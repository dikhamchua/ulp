package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code ai_providers} table (see {@code V37__ai_providers.sql}).
 *
 * <p>Each row is one OpenAI-compatible chat endpoint the platform may call.
 * {@code AiClient} iterates the enabled rows ordered by {@link #displayOrder}
 * ascending and falls back to the next provider on a transient failure.
 *
 * <p>{@link #apiKey} is stored in plain text — the same accepted technical debt as
 * the SMTP password (decision record 0008). It is never rendered into the settings
 * HTML; the UI shows a masked placeholder and fetches the real value on demand
 * through a dedicated JSON endpoint.
 *
 * <p>{@link #displayOrder} is nullable so a deleted row frees its slot under the
 * unique key without renumbering the surviving rows.
 */
@Entity
@Table(name = "ai_providers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false, length = 100, unique = true)
    private String name;

    @Setter
    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Setter
    @Column(name = "model", nullable = false, length = 150)
    private String model;

    @Setter
    @Column(name = "api_key", nullable = false, length = 500)
    private String apiKey;

    @Setter
    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Setter
    @Column(name = "display_order")
    private Short displayOrder;

    @Setter
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Maintained by the MySQL {@code ON UPDATE CURRENT_TIMESTAMP} clause, so Hibernate
     * is told not to write it — the database owns the value.
     */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a provider with the four admin-supplied fields.
     *
     * @param name     unique display name shown in the settings table
     * @param baseUrl  API root without a trailing slash (e.g. {@code https://api.openai.com/v1})
     * @param model    model identifier sent in the chat completion payload
     * @param apiKey   bearer token used to authenticate against the provider
     */
    public AiProvider(String name, String baseUrl, String model, String apiKey) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
    }
}
