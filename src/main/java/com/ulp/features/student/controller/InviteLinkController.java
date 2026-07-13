package com.ulp.features.student.controller;

import com.ulp.security.UlpUserDetails;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.invites.InviteRejectionReason;
import com.ulp.features.classes.service.invites.InviteTokenGenerator;
import com.ulp.features.classes.service.JoinClassService;
import com.ulp.features.classes.service.JoinClassService.AlreadyJoined;
import com.ulp.features.classes.service.JoinClassService.JoinResult;
import com.ulp.features.classes.service.JoinClassService.PendingRequested;
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

import static com.ulp.common.IConstant.*;

/**
 * Deep-link handler for {@code GET /j/{token}}.
 *
 * <p>Token shape is gated by a strict regex so malformed paths fall through to 404.
 * Unauthenticated clients are redirected back here after login via the request cache.
 */
@Controller
@RequestMapping("/j")
@PreAuthorize("isAuthenticated()")
public class InviteLinkController {

    private static final Pattern LINK_PATTERN = Pattern.compile(InviteTokenGenerator.LINK_REGEX);
    private static final String REDIRECT_MY_CLASSES = "redirect:/my/classes";
    private static final String REDIRECT_JOIN = "redirect:/my/classes/join";
    private static final String MSG_LINK_DISABLED = "Liên kết mời đã hết hiệu lực";
    private static final String MSG_LINK_EXPIRED = "Liên kết mời đã hết hạn";
    private static final String MSG_LINK_EXHAUSTED = "Liên kết đã đạt giới hạn lượt dùng";

    private final JoinClassService joinClassService;

    public InviteLinkController(JoinClassService joinClassService) {
        this.joinClassService = joinClassService;
    }

    /**
     * Resolves a 32-char LINK token and creates a PENDING join request (or
     * short-circuits when already ACTIVE / already PENDING).
     */
    @GetMapping("/{token}")
    public String resolve(@PathVariable("token") String token,
                          @AuthenticationPrincipal UlpUserDetails user,
                          RedirectAttributes ra) {
        if (token == null || !LINK_PATTERN.matcher(token).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MSG_INVALID_INVITE_LINK);
        }
        try {
            JoinResult outcome = joinClassService.join(token, user.getId());
            applyJoinFlash(outcome, ra);
            return REDIRECT_MY_CLASSES;
        } catch (InviteCodeValidationException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, linkMessageFor(ex.getReason()));
            return REDIRECT_JOIN;
        }
    }

    private static void applyJoinFlash(JoinResult outcome, RedirectAttributes ra) {
        if (outcome instanceof Success s) {
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_JOINED_CLASS + s.clazz().getName());
        } else if (outcome instanceof AlreadyJoined a) {
            ra.addFlashAttribute(ATTR_FLASH_INFO, MSG_ALREADY_IN_CLASS + a.clazz().getName());
        } else if (outcome instanceof PendingRequested p) {
            if (p.alreadyPending()) {
                ra.addFlashAttribute(ATTR_FLASH_INFO,
                        MSG_JOIN_ALREADY_PENDING + p.clazz().getName() + MSG_JOIN_ALREADY_PENDING_SUFFIX);
            } else {
                ra.addFlashAttribute(ATTR_FLASH_INFO,
                        MSG_JOIN_REQUEST_SENT + p.clazz().getName() + MSG_JOIN_REQUEST_PENDING_SUFFIX);
            }
        }
    }

    private static String linkMessageFor(InviteRejectionReason reason) {
        return switch (reason) {
            case INVALID -> MSG_INVALID_INVITE_LINK;
            case DISABLED -> MSG_LINK_DISABLED;
            case EXPIRED -> MSG_LINK_EXPIRED;
            case EXHAUSTED -> MSG_LINK_EXHAUSTED;
            default -> reason.getDefaultMessage();
        };
    }
}
