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
 * Entity map bang {@code system_settings} (V1__init_schema.sql).
 *
 * <p>Bang key-value chua cau hinh runtime, group theo {@code setting_group}
 * (vi du SMTP, GENERAL, OAUTH, AI). Moi setting la mot row voi
 * {@code setting_key} duy nhat.
 *
 * <p>Masking secret (vd {@code smtp.password}) duoc xu ly o service layer
 * thong qua hardcoded {@code SECRET_KEYS} set, KHONG dung column flag.
 * Cot {@code is_encrypted} co ton tai trong schema nhung khong duoc dung
 * cho masking decision o MVP nay.
 */
@Entity
@Table(name = "system_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Immutable sau khi tao — khong expose setter de tranh corrupt unique key. */
    @Column(name = "setting_key", nullable = false, length = 100, unique = true)
    private String settingKey;

    @Setter
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String settingValue;

    /** Immutable sau khi tao — group la phan dinh nghia cua setting. */
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
     * Quan ly boi MySQL trigger {@code ON UPDATE CURRENT_TIMESTAMP}
     * (xem V1__init_schema.sql). Hibernate giu {@code updatable = false}
     * de khong dap nguoc lai gia tri do DB tu set khi {@code UPDATE} SQL
     * duoc phat — Spec "sets updated_at to current timestamp" duoc satisfy
     * boi MySQL, khong phai Java code.
     */
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public SystemSetting(String settingKey, String settingValue, String settingGroup) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.settingGroup = settingGroup;
    }
}
