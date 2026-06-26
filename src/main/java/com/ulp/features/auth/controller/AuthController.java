package com.ulp.features.auth.controller;

import com.ulp.features.admin.settings.service.OauthSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.ulp.common.IConstant.*;

/**
 * MVC controller for authentication views.
 *
 * <p>Sprint 0 scope: only the login page is rendered here.
 * The {@code POST /login} and {@code /logout} endpoints are handled
 * entirely by Spring Security and require no explicit mapping.
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // ── View names ────────────────────────────────────────────────
    private static final String VIEW_LOGIN = "auth/login";

    // ── Local model attribute keys ────────────────────────────────
    private static final String ATTR_GOOGLE_ENABLED = "googleEnabled";

    // ── Error code discriminators ─────────────────────────────────
    private static final String ERROR_OAUTH_UNREGISTERED = "oauth_unregistered";

    // ── Flash messages (Vietnamese UI text) ───────────────────────
    private static final String MSG_LOGIN_FAILED =
            "Email hoặc mật khẩu không đúng, hoặc tài khoản đã bị khoá.";
    private static final String MSG_OAUTH_UNREGISTERED =
            "Email Google này chưa được đăng ký trong hệ thống. Vui lòng liên hệ quản trị viên.";
    private static final String MSG_LOGOUT_SUCCESS =
            "Bạn đã đăng xuất thành công.";

    private final OauthSettingsService oauthSettingsService;

    public AuthController(OauthSettingsService oauthSettingsService) {
        this.oauthSettingsService = oauthSettingsService;
    }

    /**
     * Renders the login page.
     *
     * <p>Resolves the toast messages and the Google-OAuth availability flag
     * server-side so the Thymeleaf template never needs to touch query
     * parameters in {@code th:attr} (Thymeleaf 3.1's restricted expression
     * mode forbids writing {@code param.*} into attributes).
     *
     * <p>The DB lookup for {@code googleEnabled} is wrapped in a try/catch:
     * a database outage must not lock every user out of the login form —
     * form login is the primary path and degrades gracefully to "no Google
     * button" when OAuth status can't be determined.
     *
     * @param error optional {@code ?error[=code]} query parameter
     * @param logout optional {@code ?logout} query parameter
     * @param model the Spring MVC model
     * @return the Thymeleaf view name {@code auth/login}
     */
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "logout", required = false) String logout,
                            Model model) {
        boolean googleEnabled = false;
        try {
            googleEnabled = oauthSettingsService.isGoogleEnabled();
        } catch (Exception ex) {
            log.warn("Failed to resolve Google OAuth availability — defaulting to disabled: {}",
                    ex.getMessage());
        }
        model.addAttribute(ATTR_GOOGLE_ENABLED, googleEnabled);

        if (error != null) {
            model.addAttribute(ATTR_FLASH_ERROR,
                    ERROR_OAUTH_UNREGISTERED.equals(error) ? MSG_OAUTH_UNREGISTERED : MSG_LOGIN_FAILED);
        }
        if (logout != null) {
            model.addAttribute(ATTR_FLASH_SUCCESS, MSG_LOGOUT_SUCCESS);
        }
        return VIEW_LOGIN;
    }
}
