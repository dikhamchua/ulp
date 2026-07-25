package com.ulp.security;

import com.ulp.entities.Permission;
import com.ulp.entities.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
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
    private final Role role;
    private final String username;
    private final String password;
    private final String fullName;
    private final boolean active;
    private final boolean locked;
    private final Collection<GrantedAuthority> authorities;

    /**
     * Constructs a {@code UlpUserDetails} from a {@link User} entity with role-only
     * authorities.
     *
     * @param user the authenticated user entity; must not be {@code null}
     */
    public UlpUserDetails(User user) {
        this(user, null);
    }

    /**
     * Constructs a {@code UlpUserDetails} carrying both role and permission authorities.
     *
     * <p>The {@code ROLE_<role>} authority is always present and is added first; the
     * {@code PERM_<feature_key>} entries derived from {@code featureKeys} are only ever
     * appended to it, never substituted for it. Every {@code hasRole} check in the
     * codebase therefore behaves exactly as it did before permissions existed.
     *
     * <p>{@code featureKeys} being {@code null} or empty is a supported, non-exceptional
     * state: it yields role-only authorities. This is what makes permission resolution
     * unable to break authentication — a caller whose resolver failed can pass
     * {@code null} and still produce a fully valid principal.
     *
     * @param user        the authenticated user entity; must not be {@code null}
     * @param featureKeys effective permission feature keys, or {@code null} for none
     */
    public UlpUserDetails(User user, Collection<String> featureKeys) {
        this.id = user.getId();
        this.role = user.getRole();
        this.username = user.getEmail();
        this.password = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.active = user.isActive();
        this.locked = user.isLocked();

        List<GrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority(user.getRole().authority()));
        if (featureKeys != null) {
            for (String featureKey : featureKeys) {
                // Skip blanks so a malformed catalogue row cannot create an empty authority.
                if (featureKey != null && !featureKey.isBlank()) {
                    granted.add(new SimpleGrantedAuthority(Permission.authorityOf(featureKey)));
                }
            }
        }
        this.authorities = List.copyOf(granted);
    }

    /**
     * Returns the ID of the currently authenticated user.
     *
     * <p>Intended for audit purposes (e.g. {@code updated_by}) in admin-facing services,
     * and for service-layer authz checks that previously required re-resolving the
     * caller's identity via {@code findByEmailIgnoreCase}.
     *
     * @return the user's primary key
     */
    public Long getId() {
        return id;
    }

    /**
     * Returns the role of the currently authenticated user.
     *
     * <p>Exposed so controllers can pass the role to service methods without
     * an additional DB lookup. Spring Security has already resolved the user
     * during authentication, and this is the authoritative role at the
     * moment of authentication.
     *
     * @return the user's {@link Role} enum value
     */
    public Role getRole() {
        return role;
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
