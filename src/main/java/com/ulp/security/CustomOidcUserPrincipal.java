package com.ulp.security;

import com.ulp.entities.User;
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
     * Returns the user's email address, which serves as the username in ULP.
     * <p>Exposes a uniform {@code principal.username} accessor so Thymeleaf
     * templates work identically for both form-login and OAuth2/OIDC sessions.</p>
     *
     * @return the user's email address
     */
    public String getUsername() {
        return username;
    }

    // â”€â”€ OidcUser delegation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Override public Map<String, Object> getClaims() { return delegate.getClaims(); }
    @Override public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }
    @Override public OidcIdToken getIdToken() { return delegate.getIdToken(); }
    @Override public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }

    /**
     * Returns the user's email address rather than the Google subject id.
     *
     * <p>{@code Authentication.getName()} is the cross-cutting identifier used
     * by Spring Security audit code, the home controller, etc. The default
     * OIDC implementation returns the {@code sub} claim (a numeric Google
     * subject), which is opaque to the rest of the application. The form-login
     * principal ({@code UlpUserDetails}) returns the email here, so we mirror
     * that for OIDC to keep callers like {@code HomeController.home()} simple.
     *
     * @return the user's email address
     */
    @Override public String getName() { return username; }
}
