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
    /** Awaiting class-owner approval after CODE/LINK self-join. */
    public static final String STATUS_PENDING = "PENDING";
    /** Class owner rejected the join request; student may re-request. */
    public static final String STATUS_REJECTED = "REJECTED";

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
     * Factory constructor for a brand new enrollment. Defaults to ACTIVE so
     * lecturer-initiated paths (IMPORT/MANUAL) keep immediate admission.
     * Invite self-join uses {@link #createPending} instead.
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
     * Factory for a fresh ACTIVE enrollment (IMPORT / MANUAL). Prefer this over
     * the raw constructor so {@code joinedVia} stays consistent.
     */
    public static Enrollment createFor(User user, Long classId,
                                       JoinedVia joinedVia, Long inviteCodeId) {
        return new Enrollment(user, classId, joinedVia.name(), inviteCodeId);
    }

    /**
     * Factory for a CODE/LINK self-join request. Status is PENDING until the
     * class owner approves; {@code use_count} is not incremented here.
     */
    public static Enrollment createPending(User user, Long classId,
                                           JoinedVia joinedVia, Long inviteCodeId) {
        Enrollment e = new Enrollment(user, classId, joinedVia.name(), inviteCodeId);
        e.status = STATUS_PENDING;
        return e;
    }

    /**
     * Revives a REMOVED enrollment as ACTIVE (lecturer-initiated re-add paths).
     * Invite re-join after REMOVED uses {@link #markPending} instead.
     */
    public void reactivateVia(String joinedVia, Long inviteCodeId) {
        this.status = STATUS_ACTIVE;
        this.joinedVia = joinedVia;
        this.inviteCodeId = inviteCodeId;
        this.completedAt = null;
    }

    /** Type-safe overload of {@link #reactivateVia(String, Long)}. */
    public void reactivateVia(JoinedVia joinedVia, Long inviteCodeId) {
        reactivateVia(joinedVia.name(), inviteCodeId);
    }

    /**
     * Opens or re-opens a join request as PENDING (REJECTED/REMOVED re-request,
     * or channel refresh on an existing pending row).
     */
    public void markPending(JoinedVia joinedVia, Long inviteCodeId) {
        this.status = STATUS_PENDING;
        this.joinedVia = joinedVia.name();
        this.inviteCodeId = inviteCodeId;
        this.completedAt = null;
    }

    /** Owner approved the request — student becomes an ACTIVE member. */
    public void activateFromPending() {
        this.status = STATUS_ACTIVE;
        this.completedAt = null;
    }

    /** Owner rejected the request; row is retained for re-request. */
    public void markRejected() {
        this.status = STATUS_REJECTED;
    }

    /** Soft-removes membership; row kept for later re-join under unique key. */
    public void markRemoved() {
        this.status = STATUS_REMOVED;
    }
}
