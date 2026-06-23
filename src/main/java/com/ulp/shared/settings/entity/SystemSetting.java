package com.ulp.shared.settings.entity;

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
 * JPA entity mapping the {@code system_settings} table (see {@code V1__init_schema.sql}).
 *
 * <p>Stores runtime configuration as key-value pairs grouped by {@code setting_group}
 * (e.g. {@code SMTP}, {@code GENERAL}, {@code OAUTH}, {@code AI}). Each setting is a
 * single row identified by a unique {@code setting_key}.
 *
 * <p>Secret masking (e.g. {@code smtp.password}) is handled at the service layer via a
 * hardcoded {@code SECRET_KEYS} set — the column flag approach is intentionally avoided.
 * The {@code is_encrypted} column exists in the schema but is not used for masking
 * decisions in this MVP.
 */
@Entity
@Table(name = "system_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Immutable after creation — no setter is exposed to prevent corruption of the unique key. */
    @Column(name = "setting_key", nullable = false, length = 100, unique = true)
    private String settingKey;

    @Setter
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    /** Immutable after creation — the group is part of the setting's identity. */
    @Column(name = "setting_group", nullable = false, length = 50)
    private String settingGroup;

    @Setter
    @Column(name = "description", length = 300)
    private String description;

    @Setter
    @Column(name = "is_encrypted")
    private boolean encrypted = false;

    @Setter
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Managed by the MySQL trigger {@code ON UPDATE CURRENT_TIMESTAMP}
     * (see {@code V1__init_schema.sql}). Hibernate uses {@code updatable = false}
     * so it does not overwrite the value that the database sets automatically on
     * every {@code UPDATE} statement — the "sets updated_at to current timestamp"
     * requirement is fulfilled by MySQL, not by Java code.
     */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * Creates a new {@code SystemSetting} with the minimum required fields.
     *
     * @param settingKey   unique key identifying this setting (e.g. {@code smtp.host})
     * @param settingValue initial value; may be {@code null}
     * @param settingGroup logical group this setting belongs to (e.g. {@code SMTP})
     */
    public SystemSetting(String settingKey, String settingValue, String settingGroup) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.settingGroup = settingGroup;
    }
}
