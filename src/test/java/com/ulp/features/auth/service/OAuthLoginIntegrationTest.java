package com.ulp.features.auth.service;

import com.ulp.security.Role;
import com.ulp.security.CustomOidcUserService;
import com.ulp.security.CustomOidcUserPrincipal;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.entities.UserOAuthProvider;
import com.ulp.features.auth.repository.UserOAuthProviderRepository;
import com.ulp.features.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link CustomOidcUserService} covering all scenarios
 * declared in {@code specs/auth-google-oauth/spec.md}:
 *
 * <ul>
 *   <li>Registered email signs in with Google ({@code registered})</li>
 *   <li>Existing user gets {@code google_id} linked + one provider row written
 *       on first sign-in</li>
 *   <li>Repeat Google sign-in does not duplicate the provider row
 *       ({@code no-duplicate-provider-row})</li>
 *   <li>Unknown email is rejected with {@code "oauth_unregistered"}
 *       ({@code unknown})</li>
 *   <li>Locked account is rejected with {@code "oauth_unregistered"}
 *       ({@code locked})</li>
 *   <li>Soft-deleted account is rejected via the entity's {@code @SQLRestriction}
 *       filter — surfaces as the same {@code "oauth_unregistered"} error</li>
 * </ul>
 *
 * <p><b>How the OIDC mock works</b> — Spring's {@link
 * org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService}
 * only calls the remote {@code userInfo} endpoint when the {@link
 * ClientRegistration} declares a non-empty {@code userInfoUri}. We build the
 * registration WITHOUT a {@code userInfoUri}, so {@code super.loadUser(request)}
 * synthesizes the {@link OidcUser} purely from the id-token claims we supply
 * ({@code sub}, {@code email}, {@code name}, ...). No HTTP call to Google is
 * ever attempted, and the test runs offline against the local MySQL.
 *
 * <p>{@code @Transactional} on the test class rolls back every persistence
 * change (linked {@code google_id} on seeded users, new locked/deleted test
 * users, {@code user_oauth_providers} rows) at the end of each method, so
 * tests stay isolated and re-runnable.
 */
@SpringBootTest
@Transactional
class OAuthLoginIntegrationTest {

    private static final String SEED_REGISTERED_EMAIL = "student@ulp.edu.vn";

    @Autowired private CustomOidcUserService customOidcUserService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserOAuthProviderRepository oauthProviderRepo;
    @Autowired private PasswordEncoder passwordEncoder;

    // ────────────────────────────────────────────────────────────────────
    //  Scenario 1+2 — registered user signs in: succeeds, links google_id,
    //  writes one user_oauth_providers row
    // ────────────────────────────────────────────────────────────────────

    @Test
    void registeredUser_signsIn_successAndLinksGoogleIdAndWritesProviderRow() {
        String sub = uniqueSub("registered");
        OidcUserRequest request = buildRequest(SEED_REGISTERED_EMAIL, sub);

        OidcUser principal = customOidcUserService.loadUser(request);

        // Returns a CustomOidcUserPrincipal exposing the local user's identity
        assertThat(principal).isInstanceOf(CustomOidcUserPrincipal.class);
        assertThat(principal.getName()).isEqualTo(SEED_REGISTERED_EMAIL);
        assertThat(authorityStrings(principal))
                .as("ROLE_<role> derived from the local users table, not Google scopes")
                .contains("ROLE_STUDENT");

        // google_id is linked on the local row
        User reloaded = userRepository.findByEmailIgnoreCase(SEED_REGISTERED_EMAIL).orElseThrow();
        assertThat(reloaded.getGoogleId()).isEqualTo(sub);

        // Exactly one user_oauth_providers row exists for (google, sub)
        Optional<UserOAuthProvider> row =
                oauthProviderRepo.findByProviderAndProviderUserId("google", sub);
        assertThat(row).isPresent();
        assertThat(row.get().getProvider()).isEqualTo("google");
        assertThat(row.get().getProviderUserId()).isEqualTo(sub);
        assertThat(row.get().getUser().getId()).isEqualTo(reloaded.getId());
    }

    // ────────────────────────────────────────────────────────────────────
    //  Scenario 3 — repeat sign-in does NOT create a duplicate provider row
    // ────────────────────────────────────────────────────────────────────

    @Test
    void repeatLogin_doesNotCreateDuplicateProviderRow() {
        String sub = uniqueSub("repeat");
        long baseline = countGoogleRowsForSub(sub);
        assertThat(baseline).isZero();

        // First sign-in — creates exactly one provider row
        customOidcUserService.loadUser(buildRequest(SEED_REGISTERED_EMAIL, sub));
        assertThat(countGoogleRowsForSub(sub)).isEqualTo(1);

        // Second sign-in (same email + same Google sub) — must be idempotent
        customOidcUserService.loadUser(buildRequest(SEED_REGISTERED_EMAIL, sub));
        assertThat(countGoogleRowsForSub(sub))
                .as("upsert: repeat sign-in must NOT duplicate user_oauth_providers")
                .isEqualTo(1);
    }

    // ────────────────────────────────────────────────────────────────────
    //  Scenario 4 — unknown email: rejected with "oauth_unregistered"
    // ────────────────────────────────────────────────────────────────────

    @Test
    void unknownEmail_isRejectedWithOauthUnregistered_andCreatesNoUser() {
        String unknownEmail = "no-such-user-" + UUID.randomUUID() + "@example.com";
        String sub = uniqueSub("unknown");

        assertThatThrownBy(() ->
                customOidcUserService.loadUser(buildRequest(unknownEmail, sub)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(
                        ((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("oauth_unregistered"));

        // No user was auto-provisioned, no provider row was written
        assertThat(userRepository.findByEmailIgnoreCase(unknownEmail)).isEmpty();
        assertThat(countGoogleRowsForSub(sub)).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    //  Scenario 5 — locked account: rejected with "oauth_unregistered"
    // ────────────────────────────────────────────────────────────────────

    @Test
    void lockedUser_isRejectedWithOauthUnregistered_andCreatesNoProviderRow() {
        String email = "test-oauth-locked-" + UUID.randomUUID() + "@example.com";
        seedUser(email, /* locked */ true, /* deleted */ false);
        String sub = uniqueSub("locked");

        assertThatThrownBy(() ->
                customOidcUserService.loadUser(buildRequest(email, sub)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(
                        ((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("oauth_unregistered"));

        // No provider row should be written for a rejected sign-in
        assertThat(countGoogleRowsForSub(sub)).isZero();

        // google_id must NOT be linked on a locked user
        User reloaded = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(reloaded.getGoogleId()).isNull();
    }

    // ────────────────────────────────────────────────────────────────────
    //  Scenario 6 — soft-deleted account: rejected (entity SQLRestriction
    //  hides it, surfaces as the same "oauth_unregistered" error)
    // ────────────────────────────────────────────────────────────────────

    @Test
    void softDeletedUser_isRejectedWithOauthUnregistered() {
        String email = "test-oauth-deleted-" + UUID.randomUUID() + "@example.com";
        seedUser(email, /* locked */ false, /* deleted */ true);
        String sub = uniqueSub("deleted");

        assertThatThrownBy(() ->
                customOidcUserService.loadUser(buildRequest(email, sub)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(ex -> assertThat(
                        ((OAuth2AuthenticationException) ex).getError().getErrorCode())
                        .isEqualTo("oauth_unregistered"));

        assertThat(countGoogleRowsForSub(sub)).isZero();
    }

    // ────────────────────────────────────────────────────────────────────
    //  Helpers
    // ────────────────────────────────────────────────────────────────────

    /**
     * Builds an {@link OidcUserRequest} whose {@link ClientRegistration} has
     * NO {@code userInfoUri}. Spring's {@code OidcUserService.shouldRetrieveUserInfo}
     * returns {@code false} in that case, so {@code super.loadUser(request)}
     * builds the OidcUser from id-token claims alone — no network call.
     */
    private static OidcUserRequest buildRequest(String email, String sub) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/google")
                .scope("openid", "email", "profile")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // userInfoUri intentionally omitted — disables the remote fetch
                .build();

        Instant now = Instant.now();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "fake-access-token",
                now,
                now.plusSeconds(3600),
                Set.of("openid", "email", "profile"));

        Map<String, Object> claims = new HashMap<>();
        claims.put(IdTokenClaimNames.SUB, sub);
        claims.put(IdTokenClaimNames.ISS, "https://accounts.google.com");
        claims.put(IdTokenClaimNames.AUD, List.of("test-client-id"));
        claims.put(IdTokenClaimNames.IAT, now);
        claims.put(IdTokenClaimNames.EXP, now.plusSeconds(3600));
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("name", "Test User");
        OidcIdToken idToken = new OidcIdToken(
                "fake-id-token",
                now,
                now.plusSeconds(3600),
                claims);

        return new OidcUserRequest(registration, accessToken, idToken);
    }

    /** Generates a unique Google subject id so tests cannot collide. */
    private static String uniqueSub(String tag) {
        return "google-sub-" + tag + "-" + UUID.randomUUID();
    }

    /** Inserts a fresh user that does not collide with seed data. */
    private void seedUser(String email, boolean locked, boolean deleted) {
        User user = UserFactory.newAdminCreated(
                email,
                passwordEncoder.encode("password"),
                "OAuth Test User",
                Role.STUDENT,
                /* emailVerified */ true,
                /* phone */ null,
                /* bio */ null);
        if (locked) {
            user.lock("test-lock-reason");
        }
        if (deleted) {
            user.softDelete();
        }
        userRepository.save(user);
    }

    private long countGoogleRowsForSub(String sub) {
        return oauthProviderRepo.findByProviderAndProviderUserId("google", sub)
                .map(row -> 1L)
                .orElse(0L);
    }

    private static List<String> authorityStrings(OidcUser principal) {
        return principal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
