package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code ai_system_prompts} table
 * (see {@code V40__ai_system_prompts.sql}).
 *
 * <p>Each row is one named, reusable system prompt an admin maintains in the
 * catalog. Screens that call AI select a prompt by {@link #name}; unlike
 * {@link AiProvider} there is no ordering column because prompts are never
 * walked as a fallback chain.
 *
 * <p>{@link #content} holds no secret, so it is rendered directly into the
 * settings HTML — no masking or on-demand reveal endpoint is needed here.
 */
@Entity
@Table(name = "ai_system_prompts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiSystemPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "name", nullable = false, length = 100, unique = true)
    private String name;

    @Setter
    @Column(name = "description", length = 500)
    private String description;

    @Setter
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Setter
    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

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
     * Creates a prompt with the three admin-supplied fields.
     *
     * @param name        unique display name shown in the catalog and in pickers
     * @param description short note on what the prompt is for; may be {@code null}
     * @param content     the system prompt body sent to the model
     */
    public AiSystemPrompt(String name, String description, String content) {
        this.name = name;
        this.description = description;
        this.content = content;
    }
}
