package com.ulp.features.classes.service;

import com.ulp.entities.ClassActivity;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.entities.Enrollment;

import java.util.Map;

/**
 * Audit-writer adapter for the student-side join/leave flow. Centralises
 * the {@code activity_classes} payload shape used by {@link JoinClassService}
 * so the orchestrating service stays focused on the enrollment state machine.
 *
 * <p>Extracted during the file-size refactor; constructed inline by
 * {@link JoinClassService} so its public constructor surface (used by tests)
 * remains unchanged.
 */
final class JoinAuditWriter {

    private final ClassActivityWriter activityWriter;

    JoinAuditWriter(ClassActivityWriter activityWriter) {
        this.activityWriter = activityWriter;
    }

    /** Writes a {@code MEMBER_JOINED} row capturing {@code user_id} and {@code joined_via}. */
    void writeJoin(ClassEntity clazz, Long userId, ClassInviteCode token) {
        activityWriter.write(
                clazz.getId(),
                ClassActivity.TYPE_MEMBER_JOINED,
                "Học viên tham gia lớp " + clazz.getName(),
                Map.of("user_id", userId, "joined_via", joinedVia(token.getType()).name()),
                userId
        );
    }

    /** Writes a {@code MEMBER_LEFT} row capturing the departing {@code user_id}. */
    void writeLeave(ClassEntity clazz, Long userId) {
        activityWriter.write(
                clazz.getId(),
                ClassActivity.TYPE_MEMBER_LEFT,
                "Học viên rời lớp " + clazz.getName(),
                Map.of("user_id", userId),
                userId
        );
    }

    /**
     * Maps the invite-token type to the {@link Enrollment.JoinedVia} channel.
     * A 6-character code maps to {@link Enrollment.JoinedVia#CODE}; anything else
     * (currently only the 32-character link type) maps to
     * {@link Enrollment.JoinedVia#LINK}.
     */
    static Enrollment.JoinedVia joinedVia(String tokenType) {
        return ClassInviteCode.TYPE_CODE.equals(tokenType)
                ? Enrollment.JoinedVia.CODE
                : Enrollment.JoinedVia.LINK;
    }
}
