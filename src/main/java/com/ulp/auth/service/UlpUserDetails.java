package com.ulp.auth.service;

import com.ulp.auth.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * UserDetails cho form-login. Ngoai cac field chuan cua Spring Security,
 * lo them {@link #getFullName()} de template dung chung accessor
 * {@code principal.fullName} cho ca form-login lan OAuth
 * (xem {@link CustomOidcUserPrincipal}).
 */
public class UlpUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String fullName;
    private final boolean active;
    private final boolean locked;
    private final Collection<GrantedAuthority> authorities;

    public UlpUserDetails(User user) {
        this.id = user.getId();
        this.username = user.getEmail();
        this.password = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.active = user.isActive();
        this.locked = user.isLocked();
        this.authorities = List.of(new SimpleGrantedAuthority(user.getRole().authority()));
    }

    /** ID cua user dang dang nhap — dung cho audit (updated_by) o cac service admin. */
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
