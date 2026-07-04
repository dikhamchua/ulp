package com.ulp.features.messaging.controller;

import com.ulp.features.messaging.service.MessagingService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import static com.ulp.common.IConstant.ATTR_MSG_UNREAD;

/**
 * Exposes the caller's unread message count to every server-rendered view so the
 * shared header fragment ({@code fragments/app-header}) can render the chat badge
 * with the correct initial number (Epic #13, ULP-8.4).
 *
 * <p>The advice is a single cheap COUNT per request; live updates thereafter are
 * pushed over STOMP by {@code messaging.js}. It contributes only a model
 * attribute, so JSON/{@code @ResponseBody} handlers simply ignore it.
 */
@ControllerAdvice
public class MessagingHeaderAdvice {

    private final MessagingService messagingService;

    public MessagingHeaderAdvice(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * Adds {@code msgUnreadCount} for authenticated users; contributes {@code 0}
     * for anonymous requests so the badge stays hidden on public pages.
     *
     * @param user the authenticated principal, or {@code null} when anonymous
     * @return the caller's total unread count, or {@code 0} when not logged in
     */
    @ModelAttribute(ATTR_MSG_UNREAD)
    public long msgUnreadCount(@AuthenticationPrincipal UlpUserDetails user) {
        if (user == null) return 0L;
        return messagingService.unreadCount(user.getId());
    }
}
