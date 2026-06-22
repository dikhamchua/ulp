package com.ulp.auth.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.entity.UserOAuthProvider;
import com.ulp.auth.repository.UserOAuthProviderRepository;
import com.ulp.auth.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Custom OIDC user service — delegate to default, then reject unregistered/locked emails.
 * Only pre-registered, active, non-locked users can sign in via Google OAuth.
 * On first sign-in, link {@code users.google_id} and upsert one {@code user_oauth_providers} row.
 */
@Service
@ConditionalOnProperty("spring.security.oauth2.client.registration.google.client-id")
public class CustomOidcUserService extends OidcUserService {

    private final UserRepository userRepository;
    private final UserOAuthProviderRepository oauthProviderRepo;

    public CustomOidcUserService(UserRepository userRepository,
                                 UserOAuthProviderRepository oauthProviderRepo) {
        this.userRepository = userRepository;
        this.oauthProviderRepo = oauthProviderRepo;
    }

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new OAuth2AuthenticationException("oauth_unregistered");
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
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
