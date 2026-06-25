package com.ulp.entities;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * JPA entity mapped to the {@code enrollments} table.
 *
 * <p>Represents the many-to-many association between a {@link User} (student)
 * and a class (identified by {@code classId}). Each row records when a student
 * joined a class, how they joined, and the current lifecycle status of that
 * enrollment.
 *
 * <p>Sprint 2 requires READ access only — used to render the member list inside
 * a class view. CREATE/REMOVE operations (Sprint 2.1) will be handled in a
 * separate change.
 */
@Entity
@Table(name = "enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /**
     * Enumerates the channels through which a student may end up enrolled in a
     * class. The enum is the source of truth in Java; the database stores the
     * {@link #name()} as a {@code VARCHAR(20)} on {@code enrollments.joined_via}
     * — DO NOT change the spelling without coordinating a Flyway migration.
     */
    public enum JoinedVia {
        /** Added by an admin or lecturer through the manual flow. */
        MANUAL,
        /** Student redeemed a 6-character invite code. */
        CODE,
        /** Student clicked a 32-character invite link. */
        LINK,
        /** Lecturer bulk-loaded the student via Excel import (ULP-3.4). */
        IMPORT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "joined_via", length = 20)
    private String joinedVia;

    @Column(name = "joined_at", insertable = false, updatable = false)
    private LocalDateTime joinedAt;

    @Column(name = "invite_code_id")
    private Long inviteCodeId;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /**
     * Factory constructor for a brand new enrollment via the join
     * flow. The {@code joinedAt} timestamp is populated by MySQL's
     * {@code DEFAULT CURRENT_TIMESTAMP} clause on first insert.
     *
     * @param user         the enrolling user
     * @param classId      target class id
     * @param joinedVia    enrollment source: {@code CODE} or {@code LINK}
     * @param inviteCodeId id of the {@link ClassInviteCode} row used
     *                     (nullable for non-token enrollments)
     */
    public Enrollment(User user, Long classId, String joinedVia, Long inviteCodeId) {
        this.user = user;
        this.classId = classId;
        this.status = STATUS_ACTIVE;
        this.joinedVia = joinedVia;
        this.inviteCodeId = inviteCodeId;
        this.completedAt = null;
    }

    /**
     * Factory for creating a fresh ACTIVE enrollment. Prefer this over the raw
     * constructor so the {@code joinedVia} string stays consistent across the
     * codebase (no stray lowercase / typo values landing in the DB).
     *
     * @param user         the enrolling user
     * @param classId      target class id
     * @param joinedVia    enrollment source as a typed enum
     * @param inviteCodeId id of the {@link ClassInviteCode} row used, or
     *                     {@code null} for non-token enrollments
     */
    public static Enrollment createFor(User user, Long classId,
                                       JoinedVia joinedVia, Long inviteCodeId) {
        return new Enrollment(user, classId, joinedVia.name(), inviteCodeId);
    }

    /**
     * Revives a previously {@code REMOVED} enrollment by flipping
     * its status back to {@code ACTIVE} and updating
     * {@code joinedVia} / {@code inviteCodeId} to reflect the new
     * channel. {@code completedAt} is cleared as a defensive measure
     * (a REMOVED row should not have a completion timestamp, but
     * this guarantees consistency).
     *
     * @param joinedVia    new enrollment source
     * @param inviteCodeId id of the new {@link ClassInviteCode} row
     */
    public void reactivateVia(String joinedVia, Long inviteCodeId) {
        this.status = STATUS_ACTIVE;
        this.joinedVia = joinedVia;
        this.inviteCodeId = inviteCodeId;
        this.completedAt = null;
    }

    /**
     * Type-safe overload of {@link #reactivateVia(String, Long)} that accepts the
     * {@link JoinedVia} enum directly. The string variant is kept for backward
     * compatibility with older call sites.
     */
    public void reactivateVia(JoinedVia joinedVia, Long inviteCodeId) {
        reactivateVia(joinedVia.name(), inviteCodeId);
    }

    /**
     * Soft-removes this enrollment by setting its status to
     * {@code REMOVED}. The row is retained so the user can later
     * re-join (which reactivates the same row via
     * {@link #reactivateVia}, preserving the unique
     * {@code (user_id, class_id)} key).
     */
    public void markRemoved() {
        this.status = STATUS_REMOVED;
    }
}
