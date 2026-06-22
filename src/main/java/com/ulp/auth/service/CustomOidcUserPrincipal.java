package com.ulp.auth.service;

import com.ulp.auth.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * OidcUser decorator that maps the ULP user's role into Spring Security authorities.
 * Delegates attribute/id-token/user-info calls to the underlying Google OidcUser.
 */
public class CustomOidcUserPrincipal implements OidcUser {

    private final OidcUser delegate;
    private final String fullName;
    private final String username;
    private final Collection<GrantedAuthority> authorities;

    public CustomOidcUserPrincipal(OidcUser delegate, User user) {
        this.delegate = delegate;
        this.fullName = user.getFullName();
        this.username = user.getEmail();
        this.authorities = List.of(new SimpleGrantedAuthority(user.getRole().authority()));
    }

    /** Exposed to Thymeleaf via {@code sec:authentication="principal.fullName"}. */
    public String getFullName() {
        return fullName;
    }

    /**
     * Email cua user (= username trong ULP). Lo accessor nay de template dung
     * chung {@code principal.username} cho ca form-login lan OAuth.
     */
    public String getUsername() {
        return username;
    }

    // ── OidcUser delegation ──────────────────────────────────

    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getName() { return delegate.getName(); }
}
