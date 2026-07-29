package com.ulp.features.profile.service;

import com.ulp.security.CustomOidcUserPrincipal;
import com.ulp.security.UlpUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionRevocationService}.
 *
 * <p>These lock in the security contract behind the password-change flow: after
 * the credential changes, every other session that the old password authorised
 * must stop working, while the session performing the change survives.</p>
 */
class SessionRevocationServiceTest {

    private static final String EMAIL = "victim@ulp.edu.vn";
    private static final String OTHER_EMAIL = "someone-else@ulp.edu.vn";

    private final SessionRegistry registry = mock(SessionRegistry.class);
    private final SessionRevocationService service = new SessionRevocationService(registry);

    /** Builds a live (non-expired) session entry for the given principal. */
    private static SessionInformation session(Object principal, String id) {
        return new SessionInformation(principal, id, new Date());
    }

    /** Stubs a principal of the form-login type reporting the given email. */
    private static UlpUserDetails formLoginPrincipal(String email) {
        UlpUserDetails principal = mock(UlpUserDetails.class);
        when(principal.getUsername()).thenReturn(email);
        return principal;
    }

    /** Stubs a Google-login principal reporting the given email. */
    private static CustomOidcUserPrincipal oidcPrincipal(String email) {
        CustomOidcUserPrincipal principal = mock(CustomOidcUserPrincipal.class);
        when(principal.getUsername()).thenReturn(email);
        return principal;
    }

    @Test
    void revokesEveryOtherSessionButKeepsTheCurrentOne() {
        Object principal = formLoginPrincipal(EMAIL);
        SessionInformation current = session(principal, "current-session");
        SessionInformation laptop = session(principal, "laptop-session");
        SessionInformation phone = session(principal, "phone-session");

        when(registry.getAllPrincipals()).thenReturn(List.of(principal));
        when(registry.getAllSessions(principal, false))
                .thenReturn(List.of(current, laptop, phone));

        int revoked = service.revokeOtherSessions(EMAIL, "current-session");

        assertThat(revoked).isEqualTo(2);
        assertThat(current.isExpired()).isFalse();
        assertThat(laptop.isExpired()).isTrue();
        assertThat(phone.isExpired()).isTrue();
    }

    @Test
    void revokesGoogleSessionsToo() {
        // An account able to sign in both ways must not keep its Google session
        // alive after a password change — email is the join key for that reason.
        Object oidc = oidcPrincipal(EMAIL);
        SessionInformation googleSession = session(oidc, "google-session");

        when(registry.getAllPrincipals()).thenReturn(List.of(oidc));
        when(registry.getAllSessions(oidc, false)).thenReturn(List.of(googleSession));

        int revoked = service.revokeOtherSessions(EMAIL, "current-session");

        assertThat(revoked).isEqualTo(1);
        assertThat(googleSession.isExpired()).isTrue();
    }

    @Test
    void leavesOtherUsersSessionsAlone() {
        Object stranger = formLoginPrincipal(OTHER_EMAIL);
        SessionInformation strangerSession = session(stranger, "stranger-session");

        when(registry.getAllPrincipals()).thenReturn(List.of(stranger));
        when(registry.getAllSessions(stranger, false)).thenReturn(List.of(strangerSession));

        int revoked = service.revokeOtherSessions(EMAIL, "current-session");

        assertThat(revoked).isZero();
        assertThat(strangerSession.isExpired()).isFalse();
    }

    @Test
    void revokesEverythingWhenNoSessionIsSpared() {
        // keepSessionId == null happens when the request has no session at all.
        Object principal = formLoginPrincipal(EMAIL);
        SessionInformation only = session(principal, "only-session");

        when(registry.getAllPrincipals()).thenReturn(List.of(principal));
        when(registry.getAllSessions(principal, false)).thenReturn(List.of(only));

        int revoked = service.revokeOtherSessions(EMAIL, null);

        assertThat(revoked).isEqualTo(1);
        assertThat(only.isExpired()).isTrue();
    }

    @Test
    void ignoresPrincipalTypesItCannotMap() {
        // A principal of an unknown type must never be matched by accident —
        // revoking a stranger's session would be worse than revoking nothing.
        Object unknown = new Object();

        when(registry.getAllPrincipals()).thenReturn(List.of(unknown));

        int revoked = service.revokeOtherSessions(EMAIL, "current-session");

        assertThat(revoked).isZero();
        verify(registry, never()).getAllSessions(any(), anyBoolean());
    }

    @Test
    void returnsZeroForBlankUsernameWithoutTouchingTheRegistry() {
        assertThat(service.revokeOtherSessions(null, "current-session")).isZero();
        assertThat(service.revokeOtherSessions("   ", "current-session")).isZero();

        verify(registry, never()).getAllPrincipals();
    }
}
