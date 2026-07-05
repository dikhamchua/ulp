package com.ulp.features.notifications.controller;

import com.ulp.features.notifications.dto.NotificationDtos.NotificationRow;
import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.UlpUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.Optional;

import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_NOTIF_UNREAD;
import static com.ulp.common.IConstant.ATTR_NOTIFICATIONS;
import static com.ulp.common.IConstant.BASE_MY_NOTIFICATIONS;
import static com.ulp.common.IConstant.MSG_NOTIF_READ;
import static com.ulp.common.IConstant.VIEW_NOTIFICATIONS_INDEX;

/**
 * Server-rendered controller for the user's notification inbox at
 * {@code /my/notifications} (Sprint 5, #63/#64).
 *
 * <p>Any authenticated user reaches these routes; per-notification access is
 * enforced in {@link NotificationService} (silent no-op for foreign/absent ids,
 * no existence leak — design decision D5).
 */
@Controller
@RequestMapping(BASE_MY_NOTIFICATIONS)
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Lists the caller's notifications, newest first. */
    @GetMapping
    public String index(@RequestParam(name = "page", defaultValue = "0") int page,
                        @AuthenticationPrincipal UlpUserDetails user, Model model) {
        Page<NotificationRow> notifications = notificationService.listForUser(user.getId(), page);
        model.addAttribute(ATTR_NOTIFICATIONS, notifications);
        // Override the pre-handler badge count so it is consistent with the list just rendered.
        model.addAttribute(ATTR_NOTIF_UNREAD, notificationService.unreadCount(user.getId()));
        return VIEW_NOTIFICATIONS_INDEX;
    }

    /**
     * Marks a notification as read (POST-Redirect-Get). Redirects to the
     * referenced resource when a reference is present, otherwise back to the list.
     *
     * <p>Foreign or absent ids are a silent no-op (no 403, no existence leak).
     */
    @PostMapping("/{notifId}/open")
    public String open(@PathVariable Long notifId,
                       @AuthenticationPrincipal UlpUserDetails user,
                       RedirectAttributes ra) {
        // Load before marking read so we can extract the reference for redirect.
        Optional<NotificationRow> before = notificationService.findOwned(user.getId(), notifId);
        notificationService.markRead(user.getId(), notifId);

        if (before.isPresent()) {
            NotificationRow n = before.get();
            String redirect = resolveRedirect(n);
            if (redirect != null) {
                return "redirect:" + redirect;
            }
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_NOTIF_READ);
        return "redirect:" + BASE_MY_NOTIFICATIONS;
    }

    /** Fallback polling endpoint: the caller's current total unread notification count. */
    @GetMapping("/unread-count")
    @ResponseBody
    public ResponseEntity<Map<String, Long>> unreadCount(
            @AuthenticationPrincipal UlpUserDetails user) {
        return ResponseEntity.ok(Map.of("count", notificationService.unreadCount(user.getId())));
    }

    /**
     * Resolves a redirect URL from a notification's reference type and id.
     * Returns {@code null} when no reference is set or the type is unknown.
     */
    private static String resolveRedirect(NotificationRow n) {
        if (n.referenceType() == null || n.referenceId() == null) return null;
        return switch (n.referenceType()) {
            // Class board tab is the canonical entry point for a class.
            case "CLASS" -> "/my/classes/" + n.referenceId();
            // Student lesson detail page.
            case "LESSON" -> "/my/lessons/" + n.referenceId();
            default -> null;
        };
    }
}
