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

    /**
     * Newly created class awaiting department HEAD review. Not operational:
     * outside the joinable whitelist, so students cannot enrol.
     */
    public static final String STATUS_DRAFT = "DRAFT";

    /** Approved by the department HEAD; operational and joinable. */
    public static final String STATUS_UPCOMING = "UPCOMING";

    /** Running class; operational and joinable. */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** Finished class; no longer joinable. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Called off by its owner; no longer joinable. */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Turned down by the department HEAD. Terminal and never joinable. */
    public static final String STATUS_REJECTED = "REJECTED";

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

    /** Owning department; always stamped from the bound subject's department. */
    @Setter
    @Column(name = "department_id")
    private Long departmentId;

    /**
     * Bound subject (môn học). Required on create/update write paths; may be
     * null only for rare legacy orphans the migration could not backfill.
     */
    @Setter
    @Column(name = "subject_id")
    private Long subjectId;

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

    /** Reviewing HEAD's user id; null until the class has been reviewed. */
    @Column(name = "approved_by")
    private Long approvedBy;

    /** Review timestamp for either outcome; null until reviewed. */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** Optional reviewer note recorded on rejection. */
    @Column(name = "rejection_note", length = 500)
    private String rejectionNote;

    /**
     * Creates a new class for the create flow.
     * The status is set to {@link #STATUS_DRAFT}: the class stays non-operational
     * until the department HEAD approves it through the approval queue.
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
        this.status = STATUS_DRAFT;
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

    /**
     * Vietnamese display label for a raw status value — the single source of
     * truth shared by the class-detail status pill and
     * {@code ClassesDtos.ClassRow.reviewStateLabel()}, so the two can never
     * drift apart.
     *
     * @param status a raw status value; may be null
     * @return the Vietnamese label, or the input unchanged when unrecognised
     */
    public static String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_DRAFT -> "Chờ duyệt";
            case STATUS_REJECTED -> "Bị từ chối";
            case STATUS_UPCOMING -> "Sắp khai giảng";
            case STATUS_ACTIVE -> "Đang hoạt động";
            case STATUS_COMPLETED -> "Đã kết thúc";
            case STATUS_CANCELLED -> "Đã huỷ";
            default -> status;
        };
    }

    /** Instance shortcut for {@link #statusLabel(String)}, for Thymeleaf. */
    public String getStatusLabel() {
        return statusLabel(this.status);
    }

    /**
     * Approves a class awaiting review, moving it to {@link #STATUS_UPCOMING}
     * so it becomes operational and joinable, and recording the reviewer.
     *
     * <p>Concurrent double-approval resolves to a single winner: the loser
     * re-reads a non-DRAFT status and fails here.
     *
     * @param reviewerId the reviewing HEAD's user id
     * @param at         the review timestamp
     * @throws IllegalStateException when the class is not {@link #STATUS_DRAFT}
     */
    public void approve(Long reviewerId, LocalDateTime at) {
        requireDraft();
        this.status = STATUS_UPCOMING;
        this.approvedBy = reviewerId;
        this.approvedAt = at;
        this.rejectionNote = null;
    }

    /**
     * Rejects a class awaiting review, moving it to the terminal
     * {@link #STATUS_REJECTED} state and recording the reviewer plus an
     * optional note. A blank note is normalised to {@code null}.
     *
     * @param reviewerId the reviewing HEAD's user id
     * @param note       optional reviewer explanation; may be null or blank
     * @param at         the review timestamp
     * @throws IllegalStateException when the class is not {@link #STATUS_DRAFT}
     */
    public void reject(Long reviewerId, String note, LocalDateTime at) {
        requireDraft();
        this.status = STATUS_REJECTED;
        this.approvedBy = reviewerId;
        this.approvedAt = at;
        this.rejectionNote = (note == null || note.isBlank()) ? null : note.trim();
    }

    /** Guards both review transitions: only a DRAFT class is reviewable. */
    private void requireDraft() {
        if (!STATUS_DRAFT.equals(this.status)) {
            throw new IllegalStateException(
                    "Lớp không còn ở trạng thái chờ duyệt");
        }
    }
}
