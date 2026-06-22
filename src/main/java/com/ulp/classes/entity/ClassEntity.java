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
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity map bang {@code classes}. Sprint 2 chua co Course phu thuoc:
 * V7 da drop FK course_id va them cot code (5 ky tu unique).
 *
 * <p>{@link SQLRestriction} dam bao moi truy van mac dinh chi lay ban ghi
 * chua bi soft-delete ({@code is_deleted = 0}).
 */
@Entity
@Table(name = "classes")
@SQLRestriction("is_deleted = 0")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Setter
    @Column(length = 10, unique = true)
    private String code;

    @Column(name = "lecturer_id", nullable = false)
    private Long lecturerId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "max_students")
    private Integer maxStudents;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "is_deleted")
    private boolean deleted = false;

    /** Constructor cho luong create. Status mac dinh UPCOMING. */
    public ClassEntity(String name, Long lecturerId, Long createdBy,
                       String description, LocalDate startDate, LocalDate endDate,
                       Integer maxStudents) {
        this.name = name;
        this.lecturerId = lecturerId;
        this.createdBy = createdBy;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.maxStudents = maxStudents != null ? maxStudents : 100;
        this.status = "UPCOMING";
    }

    // ── Business helpers ───────────────────────────────────────────

    /** Cap nhat cac truong giang vien co the chinh sua tu form. */
    public void updateDetails(String name, String description,
                              LocalDate startDate, LocalDate endDate,
                              Integer maxStudents) {
        this.name = name;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        if (maxStudents != null) {
            this.maxStudents = maxStudents;
        }
    }

    /** Danh dau soft-delete. {@code @SQLRestriction} se loc ra khoi moi truy van sau. */
    public void softDelete() {
        this.deleted = true;
    }
}
