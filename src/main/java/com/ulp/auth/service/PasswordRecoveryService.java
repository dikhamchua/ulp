package com.ulp.auth.service;

import com.ulp.auth.entity.PasswordResetToken;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.PasswordResetTokenRepository;
import com.ulp.auth.repository.UserRepository;
import com.ulp.shared.mail.MailService;
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
 * Xử lý luồng quên mật khẩu — tạo token single-use, gửi email, reset password.
 * Enumeration-safe: luôn trả về kết quả trung lập kể cả khi email không tồn tại.
 *
 * <p>Mail sending là best-effort: {@link MailService#send} trả về {@code false}
 * khi SMTP chưa cấu hình (smtp.host rỗng trong {@code system_settings}) hoặc
 * khi gửi thất bại. Trong cả hai trường hợp, một cảnh báo được log ở mức
 * {@code WARN} (không kèm token) và token được log ở mức {@code DEBUG} riêng
 * để dev có thể bật khi cần test workflow local. Token KHÔNG BAO GIỜ xuất hiện
 * ở log {@code INFO}/{@code WARN} vì các mức này thường được collect vào log
 * aggregator (Graylog, Loki, CloudWatch) — leak token = chiếm tài khoản.
 */
@Service
public class PasswordRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final SecureRandom RNG = new SecureRandom();
    /** 96 byte random → ~128 ký tự base64 URL-safe (đủ ngẫu nhiên cho reset token). */
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
     * Xử lý yêu cầu quên mật khẩu. Trả về cùng 1 thông báo cho mọi trường hợp
     * (email tồn tại hay không, SMTP fail, ...) để chống enumeration.
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
                user, rawToken, LocalDateTime.now().plusHours(TOKEN_TTL_HOURS));

        tokenRepository.save(entity);

        String link = baseUrl + "/reset-password?token=" + rawToken;
        String body = "Chào " + user.getFullName() + ",\n\n"
                + "Bạn (hoặc ai đó) đã yêu cầu đặt lại mật khẩu cho tài khoản ULP của bạn.\n\n"
                + "Nhấp vào liên kết bên dưới để đặt mật khẩu mới (hết hạn sau " + TOKEN_TTL_HOURS + " giờ):\n"
                + link + "\n\n"
                + "Nếu bạn không yêu cầu điều này, vui lòng bỏ qua email này.\n\n"
                + "— ULP Team";

        boolean sent = mailService.send(user.getEmail(),
                "Đặt lại mật khẩu ULP", body);
        if (!sent) {
            // SMTP chưa cấu hình hoặc gửi thất bại. Log WARN không kèm token
            // (mức này thường được log aggregator collect). Token chỉ log ở
            // mức DEBUG để dev có thể bật local mà không leak ra production.
            log.warn("Password-reset email NOT sent to {} (SMTP not configured or send failed). "
                    + "Token logged at DEBUG level.", user.getEmail());
            log.debug("Password-reset link for {}: {}", user.getEmail(), link);
        }
    }

    /**
     * Kiểm tra token reset có hợp lệ không (tồn tại, chưa dùng, chưa hết hạn).
     * Trả về user nếu token hợp lệ, hoặc {@code null} nếu không.
     *
     * <p>Caller không cần phân biệt lý do thất bại — luồng UX chỉ cần biết
     * "valid hay không" để render trang nhập mật khẩu mới hoặc trang lỗi
     * chung. Đây cũng là enumeration-safe (không leak "token đã dùng" vs
     * "token không tồn tại").
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
     * Đặt lại mật khẩu và đánh dấu token đã sử dụng.
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
