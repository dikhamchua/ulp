package com.ulp.features.auth.service;

import com.ulp.entities.PasswordResetToken;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.PasswordResetTokenRepository;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Handles the forgot-password flow: generates a single-use reset token, sends the
 * reset email, and applies the new password once the token is validated.
 *
 * <p>Enumeration-safe: always returns a neutral result regardless of whether the
 * requested email address exists in the system.
 *
 * <p>Mail sending is best-effort: {@link MailService#send} returns {@code false}
 * when SMTP is not configured (i.e. {@code smtp.host} is empty in
 * {@code system_settings}) or when delivery fails. In both cases a warning is
 * logged at {@code WARN} level <em>without</em> the token, and the reset link is
 * logged separately at {@code DEBUG} so developers can enable it locally when
 * testing the workflow. The token is <strong>never</strong> emitted at
 * {@code INFO} or {@code WARN} level because those levels are typically collected
 * by log aggregators (Graylog, Loki, CloudWatch) — a leaked token equals account
 * takeover.
 */
@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final SecureRandom RNG = new SecureRandom();
    /** 96 random bytes â†’ ~128 URL-safe Base64 characters; sufficient entropy for a password-reset token. */
    private static final int TOKEN_BYTES = 96;
    private static final int TOKEN_TTL_HOURS = 1;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final String baseUrl;

    public PasswordRecoveryService(UserRepository userRepository,
                                   PasswordResetTokenRepository tokenRepository,
                                   PasswordEncoder passwordEncoder,
                                   MailService mailService,
                                   @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.baseUrl = baseUrl;
    }

    /**
     * Initiates a password-reset request for the given email address.
     *
     * <p>If the email is not found the method returns silently, giving no
     * indication to the caller that the address is absent (enumeration-safe).
     * When SMTP is unavailable or the send fails, the reset link is logged at
     * {@code DEBUG} level only — see class-level Javadoc for the security
     * rationale.
     *
     * @param email the email address of the account whose password should be reset
     */
    @Transactional
    public void requestReset(String email) {
        var userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.info("Forgot-password requested for unknown email: {}", email);
            return; // silent — neutral response to avoid user enumeration
        }

        User user = userOpt.get();
        String rawToken = generateToken();
        PasswordResetToken entity = new PasswordResetToken(
                user, rawToken, LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));

        tokenRepository.save(entity);

        String link = baseUrl + "/reset-password?token=" + rawToken;
        String body = "Hi " + user.getFullName() + ",\n\n"
                + "You (or someone else) requested a password reset for your ULP account.\n\n"
                + "Click the link below to set a new password (expires in " + TOKEN_TTL_HOURS + " hour(s)):\n"
                + link + "\n\n"
                + "If you did not request this, you can safely ignore this email.\n\n"
                + "— ULP Team";

        boolean sent = mailService.send(user.getEmail(),
                "ULP Password Reset", body);
        if (!sent) {
            // SMTP is not configured or the send failed. Log at WARN without the
            // token (WARN is typically collected by log aggregators). The reset
            // link is logged at DEBUG only so developers can enable it locally
            // without leaking tokens in production.
            log.warn("Password-reset email NOT sent to {} (SMTP not configured or send failed). "
                    + "Token logged at DEBUG level.", user.getEmail());
            log.debug("Password-reset link for {}: {}", user.getEmail(), link);
        }
    }

    /**
     * Validates a password-reset token.
     *
     * <p>Returns the associated {@link User} when the token exists, has not been
     * used, and has not expired. Returns {@code null} in all failure cases.
     *
     * <p>The caller does not need to distinguish the reason for failure — the UX
     * only needs to know "valid or not" to render either the new-password form or
     * a generic error page. This is also enumeration-safe: the response does not
     * reveal whether a token was already used versus never existed.
     *
     * @param rawToken the plain-text reset token from the email link
     * @return the {@link User} that owns the token, or {@code null} if the token
     *         is invalid, expired, or already used
     */
    public User validateToken(String rawToken) {
        var opt = tokenRepository.findByToken(rawToken);
        if (opt.isEmpty()) {
            return null;
        }
        PasswordResetToken token = opt.get();
        if (!token.isValid()) {
            return null;
        }
        return token.getUser();
    }

    /**
     * Resets the user's password and marks the token as consumed.
     *
     * <p>The token must exist, be unused, and not have expired. If any of those
     * checks fail this method returns {@code false} and leaves the account
     * unchanged.
     *
     * @param rawToken    the plain-text reset token from the email link
     * @param newPassword the desired new password in plain text (will be BCrypt-encoded)
     * @return {@code true} if the password was updated successfully;
     *         {@code false} if the token is invalid, expired, or already used
     */
    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        var opt = tokenRepository.findByToken(rawToken);
        if (opt.isEmpty()) {
            return false;
        }
        PasswordResetToken token = opt.get();
        if (!token.isValid()) {
            return false;
        }

        User user = token.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.markUsed();
        tokenRepository.save(token);

        return true;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
