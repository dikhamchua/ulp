package com.ulp.features.notifications.controller;

import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import static com.ulp.common.IConstant.ATTR_NOTIF_UNREAD;

/**
 * Exposes the caller's unread notification count to every server-rendered view
 * so the shared header fragment ({@code fragments/app-header}) can render the
 * bell badge with the correct initial number (Sprint 5, #63).
 *
 * <p>The advice contributes a single cheap COUNT per request; the JS polling
 * in {@code notifications.js} keeps it current after initial load.
 * It contributes only a model attribute, so JSON/{@code @ResponseBody} handlers
 * simply ignore it.
 */
@ControllerAdvice
public class NotificationHeaderAdvice {

    private final NotificationService notificationService;

    public NotificationHeaderAdvice(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Adds {@code notifUnreadCount} for authenticated users; contributes
     * {@code 0} for anonymous requests so the badge stays hidden on public pages.
     *
     * @param user the authenticated principal, or {@code null} when anonymous
     * @return the caller's total unread notification count, or {@code 0} when not logged in
     */
    @ModelAttribute(ATTR_NOTIF_UNREAD)
    public long notifUnreadCount(@AuthenticationPrincipal UlpUserDetails user) {
        if (user == null) return 0L;
        return notificationService.unreadCount(user.getId());
    }
}
