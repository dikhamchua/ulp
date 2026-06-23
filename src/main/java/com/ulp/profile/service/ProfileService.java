package com.ulp.profile.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;

/**
 * Service for reading and updating the current user's profile information.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Retrieves the currently authenticated user by resolving their email from the given {@link Principal}.
     *
     * @param principal the security principal whose {@code name} is the user's email address
     * @return the {@link User} entity associated with the authenticated principal
     * @throws IllegalStateException if no user with the principal's email is found in the database
     */
    public User getCurrentUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB"));
    }

    /**
     * Updates the display name, biography, and phone number of the given user and persists the changes.
     *
     * @param user     the {@link User} entity to update
     * @param fullName the new full name; {@code null} or blank values are handled by {@link User#updateProfile}
     * @param bio      the new biography text
     * @param phone    the new phone number
     * @return the saved {@link User} entity reflecting the updates
     */
    @Transactional
    public User updateProfile(User user, String fullName, String bio, String phone) {
        user.updateProfile(fullName, bio, phone);
        return userRepository.save(user);
    }

    /**
     * Updates the avatar URL of the given user and persists the change.
     *
     * @param user      the {@link User} entity whose avatar is being updated
     * @param avatarUrl the new avatar URL (typically a path under {@code /uploads/})
     * @return the saved {@link User} entity with the updated avatar URL
     */
    @Transactional
    public User updateAvatar(User user, String avatarUrl) {
        user.setAvatarUrl(avatarUrl);
        return userRepository.save(user);
    }
}
