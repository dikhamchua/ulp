package com.ulp.features.classes.service.invites;

/**
 * Thrown when a join attempt is rejected for any business-rule reason.
 * The {@link InviteRejectionReason} carries the user-facing Vietnamese
 * message; controllers can use {@link #getMessage()} directly to surface
 * the rejection to the user.
 */
public class InviteCodeValidationException extends RuntimeException {

    private final InviteRejectionReason reason;

    public InviteCodeValidationException(InviteRejectionReason reason) {
        super(reason.getDefaultMessage());
        this.reason = reason;
    }

    public InviteRejectionReason getReason() {
        return reason;
    }
}
