package com.ulp.auth.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final UserRepository userRepository;

    /**
     * Constructs the service with the required user repository.
     *
     * @param userRepository JPA repository used to look up {@link User} records by email
     */
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + email));

        // UlpUserDetails maps roles to ROLE_<name> and exposes isEnabled()/isAccountNonLocked()
        // so that Spring Security throws DisabledException / LockedException respectively.
        return new UlpUserDetails(user);
    }
}
