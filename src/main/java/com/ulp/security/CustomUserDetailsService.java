package com.ulp.security;

import com.ulp.entities.User;
import com.ulp.features.admin.permissions.service.PermissionResolver;
import com.ulp.features.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Loads user authentication data from the database for Spring Security.
 *
 * <p>In ULP, users authenticate with their <b>email address</b>, so the
 * {@code username} parameter passed by Spring Security is treated as an email.
 * Returns a {@link UlpUserDetails} principal that exposes {@code fullName},
 * enabling Thymeleaf templates to use a shared accessor across both
 * form-login and OAuth flows (see {@link CustomOidcUserPrincipal}).
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final UserRepository userRepository;
    private final PermissionResolver permissionResolver;

    /**
     * Constructs the service with the required collaborators.
     *
     * @param userRepository     JPA repository used to look up {@link User} records by email
     * @param permissionResolver resolves the user's effective permission set
     */
    public CustomUserDetailsService(UserRepository userRepository,
                                    PermissionResolver permissionResolver) {
        this.userRepository = userRepository;
        this.permissionResolver = permissionResolver;
    }

    /**
     * Locates a {@link User} by email address and wraps it in a {@link UlpUserDetails} principal.
     *
     * <p>This method is called by Spring Security during form-login authentication.
     * The {@code username} parameter is treated as an email, which is the unique
     * login identifier in ULP.
     *
     * @param email the email address submitted on the login form
     * @return a fully-populated {@link UlpUserDetails} for the matching account
     * @throws UsernameNotFoundException if no account with the given email exists
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + email));

        // UlpUserDetails maps roles to ROLE_<name> and exposes isEnabled()/isAccountNonLocked()
        // so that Spring Security throws DisabledException / LockedException respectively.
        return new UlpUserDetails(user, resolvePermissionsSafely(user.getId()));
    }

    /**
     * Resolves the user's effective permissions without ever failing authentication.
     *
     * <p>Permission resolution is an enhancement layered on top of roles, not a
     * precondition for logging in. If the resolver throws for any reason — database
     * unavailable, the RBAC tables missing because the migration has not been applied
     * yet, a malformed row — this method swallows the failure and returns an empty set.
     * The caller then builds a principal with role-only authorities and login proceeds
     * exactly as it did before permissions existed.
     *
     * @param userId the authenticated user's id
     * @return the effective feature keys, or an empty set when resolution fails
     */
    private Set<String> resolvePermissionsSafely(Long userId) {
        try {
            Set<String> featureKeys = permissionResolver.resolvePermissions(userId);
            return featureKeys == null ? Set.of() : featureKeys;
        } catch (Exception ex) {
            // Degrade to role-only authorities rather than blocking the login.
            log.warn("Permission resolution failed for user {}; continuing with role-only "
                    + "authorities", userId, ex);
            return Set.of();
        }
    }
}
