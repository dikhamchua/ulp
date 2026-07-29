package com.ulp.features.profile.service;

import com.ulp.security.CustomOidcUserPrincipal;
import com.ulp.security.UlpUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Expires a user's authenticated sessions, optionally sparing the one that
 * requested the change.
 *
 * <p>Used after a password change: the credential that authorised every live
 * session is no longer the credential on file, so those sessions must not
 * survive. Sparing the current session keeps the user logged in on the device
 * they are actually using, which is the behaviour people expect and avoids an
 * abrupt bounce to the login page.</p>
 */
@Service
public class SessionRevocationService {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final SessionRegistry sessionRegistry;

    public SessionRevocationService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every session belonging to the given user except {@code keepSessionId}.
     *
     * <p>Expiring is deliberate rather than invalidating: Spring Security's
     * {@code ConcurrentSessionFilter} sees the expired marker on the next
     * request from that session and logs it out cleanly. Sessions already
     * marked expired are skipped so a repeated call is a no-op.</p>
     *
     * @param username      the email of the user whose sessions should be revoked
     * @param keepSessionId the session id to spare, or {@code null} to revoke all
     * @return the number of sessions newly marked as expired
     */
    public int revokeOtherSessions(String username, String keepSessionId) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        int revoked = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!username.equals(usernameOf(principal))) {
                continue;
            }
            // includeExpiredSessions=false — already-expired entries need no work.
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            for (SessionInformation session : sessions) {
                if (session.getSessionId().equals(keepSessionId)) {
                    continue;
                }
                session.expireNow();
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("Revoked {} session(s) for user {} after credential change", revoked, username);
        }
        return revoked;
    }

    /**
     * Extracts the ULP username (email) from a principal recorded by the registry.
     *
     * <p>Email is the join key rather than the numeric user id because the two
     * principal types disagree on what they expose: {@link UlpUserDetails}
     * carries the id, while {@link CustomOidcUserPrincipal} does not. Both
     * return the user's email as their username, so matching on it revokes
     * form-login and Google sessions alike — which matters for an account that
     * can sign in either way.</p>
     *
     * @param principal the principal recorded by the session registry
     * @return the username, or {@code null} for a principal type we cannot map
     */
    private String usernameOf(Object principal) {
        if (principal instanceof UlpUserDetails details) {
            return details.getUsername();
        }
        if (principal instanceof CustomOidcUserPrincipal oidc) {
            return oidc.getUsername();
        }
        return null;
    }
}
