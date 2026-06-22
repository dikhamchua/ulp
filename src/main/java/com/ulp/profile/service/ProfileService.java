package com.ulp.profile.service;

import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;

/**
 * Doc va ghi thong tin profile cua nguoi dung hien tai.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Lay user hien tai tu Principal (email = username). */
    public User getCurrentUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB"));
    }

    /** Cap nhat fullName, bio, phone. */
    @Transactional
    public User updateProfile(User user, String fullName, String bio, String phone) {
        user.updateProfile(fullName, bio, phone);
        return userRepository.save(user);
    }

    /** Cap nhat avatarUrl. */
    @Transactional
    public User updateAvatar(User user, String avatarUrl) {
        user.setAvatarUrl(avatarUrl);
        return userRepository.save(user);
    }
}
