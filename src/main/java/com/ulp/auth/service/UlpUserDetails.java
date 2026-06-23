package com.ulp.auth.service;

import com.ulp.auth.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security {@link UserDetails} implementation for form-based login.
 *
 * <p>In addition to the standard Spring Security fields, this class exposes
 * {@link #getFullName()} so that Thymeleaf templates can use the unified
 * {@code principal.fullName} accessor for both form-login and OAuth principals
 * (see {@link CustomOidcUserPrincipal}).
 */
public class UlpUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String fullName;
    private final boolean active;
    private final boolean locked;
    private final Collection<GrantedAuthority> authorities;

    /**
     * Constructs a {@code UlpUserDetails} from a {@link User} entity.
     *
     * @param user the authenticated user entity; must not be {@code null}
     */
    public UlpUserDetails(User user) {
        this.id = user.getId();
        this.username = user.getEmail();
        this.password = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.active = user.isActive();
        this.locked = user.isLocked();
        this.authorities = List.of(new SimpleGrantedAuthority(user.getRole().authority()));
    }

    /**
     * Returns the ID of the currently authenticated user.
     *
     * <p>Intended for audit purposes (e.g. {@code updated_by}) in admin-facing services.
     *
     * @return the user's primary key
     */
    public Long getId() {
        return id;
    }

    /** Exposed to Thymeleaf via {@code sec:authentication="principal.fullName"}. */
    public String getFullName() {
        return fullName;
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !locked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
