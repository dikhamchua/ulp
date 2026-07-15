package com.ulp.features.notifications.controller;

import com.ulp.features.notifications.dto.NotificationDtos.NotificationRow;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.UlpUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    /** Recent items returned to the header dropdown panel. */
    private static final int DROPDOWN_LIMIT = 8;
    private static final DateTimeFormatter DROPDOWN_TIME =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

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
     * AJAX clients (header dropdown) receive JSON instead of a redirect.
     */
    @PostMapping("/{notifId}/open")
    public Object open(@PathVariable Long notifId,
                       @AuthenticationPrincipal UlpUserDetails user,
                       RedirectAttributes ra,
                       @RequestParam(name = "ajax", required = false) String ajax,
                       @RequestHeader(value = "X-Requested-With", required = false)
                       String requestedWith) {
        // Load before marking read so we can extract the reference for redirect.
        Optional<NotificationRow> before = notificationService.findOwned(user.getId(), notifId);
        notificationService.markRead(user.getId(), notifId);

        String redirect = before.map(NotificationController::resolveRedirect).orElse(null);
        long unread = notificationService.unreadCount(user.getId());

        // Header dropdown uses fetch + X-Requested-With or ajax=1.
        if (isAjax(ajax, requestedWith)) {
            Map<String, Object> body = new HashMap<>();
            body.put("ok", true);
            body.put("redirect", redirect != null ? redirect : BASE_MY_NOTIFICATIONS);
            body.put("count", unread);
            return ResponseEntity.ok(body);
        }

        if (redirect != null) {
            return "redirect:" + redirect;
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
     * Recent notifications for the header dropdown panel (Facebook-style).
     * Returns newest first, capped for panel height.
     */
    @GetMapping(value = "/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> recent(
            @AuthenticationPrincipal UlpUserDetails user) {
        Page<NotificationRow> page = notificationService.listForUser(user.getId(), 0);
        List<Map<String, Object>> items = page.getContent().stream()
                .limit(DROPDOWN_LIMIT)
                .map(this::toDropdownItem)
                .collect(Collectors.toList());
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("count", notificationService.unreadCount(user.getId()));
        return ResponseEntity.ok(body);
    }

    /** Builds one JSON row for the dropdown list. */
    private Map<String, Object> toDropdownItem(NotificationRow n) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", n.id());
        item.put("title", n.title());
        item.put("content", n.content());
        item.put("isRead", n.isRead());
        item.put("createdAt", n.createdAt() != null ? DROPDOWN_TIME.format(n.createdAt()) : "");
        item.put("href", resolveRedirect(n));
        return item;
    }

    private static boolean isAjax(String ajaxFlag, String requestedWith) {
        return "1".equals(ajaxFlag) || "true".equalsIgnoreCase(ajaxFlag)
                || "XMLHttpRequest".equalsIgnoreCase(requestedWith);
    }

    /**
     * Resolves a redirect URL from a notification's reference type and id.
     * Returns {@code null} when no reference is set or the type is unknown.
     *
     * <p>{@code JOIN_REQUEST} is lecturer-facing and must land on the class
     * Members tab under {@code /lecturer/classes/...}. Other CLASS notifications
     * keep the student lessons entry (ACTIVE members) as the default.
     */
    private static String resolveRedirect(NotificationRow n) {
        if (n.referenceType() == null || n.referenceId() == null) return null;
        return switch (n.referenceType()) {
            case "CLASS" -> resolveClassRedirect(n);
            // Student lesson detail page.
            case "LESSON" -> "/my/lessons/" + n.referenceId();
            default -> null;
        };
    }

    /** CLASS targets depend on notification type (owner queue vs student class). */
    private static String resolveClassRedirect(NotificationRow n) {
        // Lecturer must open the pending queue; /my/classes/{id} is not a real route.
        if (NotificationType.JOIN_REQUEST.equals(n.type())) {
            return "/lecturer/classes/" + n.referenceId() + "/members";
        }
        // Admitted students land on published lessons for the class.
        return "/my/classes/" + n.referenceId() + "/lessons";
    }
}
