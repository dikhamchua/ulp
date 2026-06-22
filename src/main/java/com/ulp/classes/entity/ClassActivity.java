package com.ulp.classes.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity map bang {@code activity_classes}. Audit log cho moi mutation
 * tren {@link ClassEntity}: CREATED, UPDATED, DELETED, ...
 *
 * <p><b>Quan trong:</b> {@code classId} la plain {@code Long}, KHONG dung
 * {@code @ManyToOne} toi {@link ClassEntity}. Ly do: {@code ClassEntity}
 * co {@code @SQLRestriction("is_deleted = 0")} nen mot lop bi soft-delete
 * van can co audit row, ma JPA join se loc ban ghi nay ra. Bao toan
 * audit log tach roi vong doi lop.
 */
@Entity
@Table(name = "activity_classes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassActivity {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_UPDATED = "UPDATED";
    public static final String TYPE_STARTED = "STARTED";
    public static final String TYPE_COMPLETED = "COMPLETED";
    public static final String TYPE_CANCELLED = "CANCELLED";
    public static final String TYPE_DELETED = "DELETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public ClassActivity(Long classId, String type, String description,
                         String metadata, Long createdBy) {
        this.classId = classId;
        this.type = type;
        this.description = description;
        this.metadata = metadata;
        this.createdBy = createdBy;
    }
}
