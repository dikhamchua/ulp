package com.ulp.auth.service;

import com.ulp.auth.entity.PasswordResetToken;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.PasswordResetTokenRepository;
import com.ulp.auth.repository.UserRepository;
import com.ulp.shared.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Xu ly luong quen mat khau — tao token single-use, gui email, reset password.
 * Enumeration-safe: luon tra ve ket qua trung lap ke ca khi email khong ton tai.
 * Mail sending is best-effort (silently skipped when SMTP is not configured).
 */
@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    private MailService mailService;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    public PasswordRecoveryService(UserRepository userRepository,
                                   PasswordResetTokenRepository tokenRepository,
                                   PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Xu ly yeu cau quen mat khau. Tra ve cung 1 thong bao cho moi truong hop
     * (email ton tai hay khong, SMTP fail, ...) de chong enumeration.
     */
    @Transactional
    public void requestReset(String email) {
        var userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            log.info("Forgot-password requested for unknown email: {}", email);
            return; // silent — neutral UX
        }

        User user = userOpt.get();
        String rawToken = generateToken();
        PasswordResetToken entity = new PasswordResetToken(
                user, rawToken, LocalDateTime.now().plusHours(1));

        tokenRepository.save(entity);

        if (mailService != null) {
            String link = baseUrl + "/reset-password?token=" + rawToken;
            String body = "Chào " + user.getFullName() + ",\n\n"
                    + "Bạn (hoặc ai đó) đã yêu cầu đặt lại mật khẩu cho tài khoản ULP của bạn.\n\n"
                    + "Nhấp vào liên kết bên dưới để đặt mật khẩu mới (hết hạn sau 1 giờ):\n"
                    + link + "\n\n"
                    + "Nếu bạn không yêu cầu điều này, vui lòng bỏ qua email này.\n\n"
                    + "— ULP Team";

            boolean sent = mailService.send(user.getEmail(),
                    "Đặt lại mật khẩu ULP", body);
            if (!sent) {
                log.warn("Failed to send password-reset email to {}", user.getEmail());
            }
        } else {
            log.info("Mail not configured — reset token created for {} but email NOT sent. Token: {}",
                    user.getEmail(), baseUrl + "/reset-password?token=" + rawToken);
        }
    }

    /**
     * Kiem tra token reset co hop le khong (ton tai, chua dung, chua het han).
     * Tra ve user neu token hop le.
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
     * Dat lai mat khau va danh dau token da su dung.
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
        byte[] bytes = new byte[96]; // -> 128 chars base64
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
