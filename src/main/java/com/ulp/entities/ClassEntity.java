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
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code classes} table.
 *
 * <p>As of Sprint 2 there is no Course dependency: migration V7 dropped the
 * {@code course_id} foreign key and added the {@code code} column
 * (5-character unique identifier).
 *
 * <p>{@link SQLRestriction} ensures that every default query filters out
 * soft-deleted records ({@code is_deleted = 0}).
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

    /**
     * Creates a new class for the create flow.
     * The status is set to {@code UPCOMING} by default.
     * If {@code maxStudents} is {@code null}, it defaults to {@code 100}.
     *
     * @param name        display name of the class
     * @param lecturerId  ID of the assigned lecturer
     * @param createdBy   ID of the user who created this class
     * @param description optional text description
     * @param startDate   scheduled start date
     * @param endDate     scheduled end date
     * @param maxStudents maximum number of enrolled students; {@code null} defaults to 100
     */
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

    /**
     * Updates the editable fields that a lecturer may change via the edit form.
     * {@code maxStudents} is only applied when non-null, preserving the current
     * value if the caller omits it.
     *
     * @param name        new display name
     * @param description new description text
     * @param startDate   new start date
     * @param endDate     new end date
     * @param maxStudents new student cap, or {@code null} to leave unchanged
     */
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

    /**
     * Marks this class as soft-deleted.
     * After this call the {@link SQLRestriction} on the entity will exclude
     * this record from all default queries.
     */
    public void softDelete() {
        this.deleted = true;
    }
}
