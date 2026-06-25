package com.ulp.entities;

import com.ulp.security.Role;

/**
 * Package-private factory for constructing new {@link User} instances.
 *
 * <p>{@link User} hides its no-arg constructor behind
 * {@code @NoArgsConstructor(access = PROTECTED)} to prevent ad-hoc external
 * instantiation. The admin Create flow needs to build a fresh user with all
 * mandatory fields populated â€” this factory provides exactly that surface and
 * lives in the entity's own package so it can call the protected constructor.
 */
public final class UserFactory {

    private UserFactory() {
        // utility holder
    }

    /**
     * Builds a fully-populated {@link User} for the admin Create flow.
     *
     * @param normalizedEmail email already trimmed and lower-cased by the service
     * @param passwordHash    a BCrypt hash; callers MUST encode before passing
     * @param fullName        display name
     * @param role            role to assign on creation
     * @param emailVerified   initial value for {@code is_email_verified}
     * @param phone           optional phone (already null-coerced when blank)
     * @param bio             optional bio (already null-coerced when blank)
     * @return a {@link User} entity ready for {@code repository.save(...)}
     */
    public static User newAdminCreated(String normalizedEmail,
                                       String passwordHash,
                                       String fullName,
                                       Role role,
                                       boolean emailVerified,
                                       String phone,
                                       String bio) {
        return new User(
                normalizedEmail,
                passwordHash,
                fullName,
                role,
                emailVerified,
                /* active  */ true,
                /* locked  */ false,
                /* deleted */ false,
                phone,
                bio
        );
    }
}
