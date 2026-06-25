package com.ulp.security;

import com.ulp.entities.User;
import com.ulp.entities.UserOAuthProvider;
import com.ulp.features.auth.repository.UserOAuthProviderRepository;
import com.ulp.features.auth.repository.UserRepository;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Custom OIDC user service â€” delegate to the default service, then enforce
 * ULP's registration rules. Only pre-registered, active, non-locked users may
 * sign in via Google OAuth. On first sign-in we link {@code users.google_id}
 * and upsert one {@code user_oauth_providers} row.
 *
 * <p>This bean is always registered. When Google sign-in is not configured
 * (no client id in {@code system_settings}), Spring Security simply never
 * dispatches an OIDC request here â€” see {@code DbClientRegistrationRepository}.
 */
@Service
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;
    private final UserOAuthProviderRepository oauthProviderRepo;

    /**
     * Constructs the service with the required repositories.
     *
     * @param userRepository    repository for looking up and persisting {@link User} records
     * @param oauthProviderRepo repository for upserting {@link UserOAuthProvider} link rows
     */
    public CustomOidcUserService(UserRepository userRepository,
                                 UserOAuthProviderRepository oauthProviderRepo) {
        this.userRepository = userRepository;
        this.oauthProviderRepo = oauthProviderRepo;
    }

    /**
     * Loads and validates the OIDC user after a successful Google sign-in.
     *
     * <p>Delegates to the default {@link OidcUserService} to fetch the user info,
     * then applies the following business rules:
     * <ul>
     *   <li>Rejects any account whose email is missing or blank.</li>
     *   <li>Rejects emails that are not pre-registered in the {@code users} table.</li>
     *   <li>Rejects accounts that are locked ({@code is_locked = true}).
     *       Soft-deleted accounts are already excluded by the {@code @SQLRestriction} on {@link User}.</li>
     *   <li>On first sign-in, stores the Google subject ID in {@code users.google_id}.</li>
     *   <li>Upserts a {@code user_oauth_providers} row (provider = {@code "google"})
     *       to avoid duplicates on subsequent logins.</li>
     * </ul>
     *
     * @param userRequest the OIDC user request containing the access token and client registration
     * @return a {@link CustomOidcUserPrincipal} wrapping the OIDC user and the local {@link User}
     * @throws OAuth2AuthenticationException with error code {@code "oauth_unregistered"} if the
     *         email is absent, not found in the database, or the account is locked
     */
    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("oauth_unregistered");
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            throw new OAuth2AuthenticationException("oauth_unregistered");
        }

        User user = userOpt.get();
        if (user.isLocked()) {
            throw new OAuth2AuthenticationException("oauth_unregistered");
        }
        // is_deleted already filtered by @SQLRestriction

        // Link google_id if not yet set
        String googleSub = oidcUser.getSubject();
        if (user.getGoogleId() == null || user.getGoogleId().isBlank()) {
            user.setGoogleId(googleSub);
            userRepository.save(user);
        }

        // Upsert oauth provider row (no duplicate)
        Optional<UserOAuthProvider> existing = oauthProviderRepo
                .findByProviderAndProviderUserId("google", googleSub);
        if (existing.isEmpty()) {
            oauthProviderRepo.save(new UserOAuthProvider(user, "google", googleSub));
        }

        // Build OidcUser with correct authorities from the user's role
        return new CustomOidcUserPrincipal(oidcUser, user);
    }
}
