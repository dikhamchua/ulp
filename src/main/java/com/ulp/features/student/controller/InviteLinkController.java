package com.ulp.features.student.controller;

import com.ulp.security.UlpUserDetails;
import com.ulp.features.classes.service.InviteCodeValidationException;
import com.ulp.features.classes.service.InviteRejectionReason;
import com.ulp.features.classes.service.InviteTokenGenerator;
import com.ulp.features.classes.service.JoinClassService;
import com.ulp.features.classes.service.JoinClassService.AlreadyJoined;
import com.ulp.features.classes.service.JoinClassService.JoinResult;
import com.ulp.features.classes.service.JoinClassService.Success;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.regex.Pattern;

/**
 * Deep-link handler for {@code GET /j/{token}}.
 *
 * <p>Token shape is gated by a strict regex so malformed paths fall
 * through to a 404. When an unauthenticated client hits this URL,
 * Spring Security's request cache picks up the original URI and
 * the user is redirected back here after a successful login.
 */
@Controller
@RequestMapping("/j")
@PreAuthorize("isAuthenticated()")
public class InviteLinkController {

    /** Server-side regex matching the 32-char base64url LINK shape. */
    private static final Pattern LINK_PATTERN = Pattern.compile(InviteTokenGenerator.LINK_REGEX);

    private final JoinClassService joinClassService;

    public InviteLinkController(JoinClassService joinClassService) {
        this.joinClassService = joinClassService;
    }

    /**
     * Resolves a 32-char LINK token and either enrolls the caller
     * or surfaces the validation error as a flash toast.
     *
     * <p>Token shape that does not match {@link #LINK_PATTERN}
     * returns 404 — short tokens, accidental URL fragments, and
     * any tampering all collapse to the same neutral response.
     */
    @GetMapping("/{token}")
    public String resolve(@PathVariable("token") String token,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        if (token == null || !LINK_PATTERN.matcher(token).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Liên kết không hợp lệ");
        }
        try {
            JoinResult outcome = joinClassService.join(token, user.getId());
            if (outcome instanceof Success s) {
                ra.addFlashAttribute("flashSuccess", "Đã tham gia lớp " + s.clazz().getName());
            } else if (outcome instanceof AlreadyJoined a) {
                ra.addFlashAttribute("flashInfo", "Bạn đã ở trong lớp " + a.clazz().getName());
            }
            return "redirect:/my/classes";
        } catch (InviteCodeValidationException ex) {
            ra.addFlashAttribute("flashError", linkMessageFor(ex.getReason()));
            return "redirect:/my/classes/join";
        }
    }

    /**
     * Maps a rejection reason to a LINK-flavoured message. The {@code /j/} surface
     * historically referred to "Liên kết" (link) rather than "Mã" (code), so the
     * INVALID / DISABLED / EXPIRED / EXHAUSTED reasons use link-specific copy.
     * All other reasons fall through to the enum's default message.
     */
    private static String linkMessageFor(InviteRejectionReason reason) {
        return switch (reason) {
            case INVALID -> "Liên kết không hợp lệ";
            case DISABLED -> "Liên kết mời đã hết hiệu lực";
            case EXPIRED -> "Liên kết mời đã hết hạn";
            case EXHAUSTED -> "Liên kết đã đạt giới hạn lượt dùng";
            default -> reason.getDefaultMessage();
        };
    }
}
