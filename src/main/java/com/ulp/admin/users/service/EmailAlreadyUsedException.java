package com.ulp.admin.users.service;

/**
 * Thrown by {@code AdminUsersService} when a Create or Edit submission
 * carries an email that already belongs to another (active or soft-deleted)
 * user. The controller layer catches this to render an inline field error
 * on the form's {@code email} input rather than producing a generic 500.
 */
public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException(String email) {
        super("Email đã được sử dụng: " + email);
    }
}
