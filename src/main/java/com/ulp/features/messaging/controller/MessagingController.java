package com.ulp.features.messaging.controller;

import com.ulp.features.messaging.dto.MessagingDtos.ConversationRow;
import com.ulp.features.messaging.dto.MessagingDtos.ConversationView;
import com.ulp.features.messaging.dto.MessagingDtos.RecipientRow;
import com.ulp.features.messaging.dto.MessagingDtos.SendResult;
import com.ulp.features.messaging.service.MessagingService;
import com.ulp.security.UlpUserDetails;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_COMPOSE;
import static com.ulp.common.IConstant.ATTR_COMPOSE_QUERY;
import static com.ulp.common.IConstant.ATTR_CONVERSATION;
import static com.ulp.common.IConstant.ATTR_CONVERSATIONS;
import static com.ulp.common.IConstant.ATTR_MSG_UNREAD;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_RECIPIENTS;
import static com.ulp.common.IConstant.BASE_MY_MESSAGES;
import static com.ulp.common.IConstant.VIEW_MESSAGING_INDEX;

/**
 * Server-rendered controller for direct messaging under {@code /my/messages}
 * (Epic #13, ULP-8.3). Any authenticated user reaches these routes; per-thread
 * access is enforced in {@link MessagingService} (404 no-leak when the caller is
 * not a participant), and the recipient gate applies at conversation creation.
 *
 * <p>The composer submits via {@code fetch} when JS is on (see
 * {@code static/js/messaging.js}); the same {@code POST} endpoint also accepts a
 * plain form submit and redirects back, so messaging degrades gracefully without
 * JS (design decision D6).
 */
@Controller
@RequestMapping(BASE_MY_MESSAGES)
@PreAuthorize("isAuthenticated()")
public class MessagingController {

    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /** Lists the caller's conversations; the right pane starts empty. */
    @GetMapping
    public String index(@RequestParam(name = "page", defaultValue = "0") int page,
                        @AuthenticationPrincipal UlpUserDetails user, Model model) {
        Page<ConversationRow> conversations = messagingService.listConversations(user.getId(), page);
        model.addAttribute(ATTR_CONVERSATIONS, conversations);
        // Keep compose non-null so the template's !compose checks never hit a null.
        model.addAttribute(ATTR_COMPOSE, false);
        model.addAttribute(ATTR_PAGER_PARAMS, new LinkedHashMap<String, Object>());
        return VIEW_MESSAGING_INDEX;
    }

    /**
     * Opens one conversation: sidebar list on the left, thread on the right. The
     * default page ({@code -1}) resolves in the service to the NEWEST page of
     * messages, so opening a long thread lands on the most recent messages; the
     * pager can then walk toward older pages (lower indices).
     */
    @GetMapping("/{convId}")
    public String open(@PathVariable Long convId,
                       @RequestParam(name = "page", defaultValue = "-1") int page,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        // Mark read FIRST: the sidebar pill and header badge must reflect the
        // cleared state in this same render. listConversations reads the pill
        // counts, so it must run after markRead; the header badge from the
        // @ModelAttribute advice ran before this handler, so override it below.
        ConversationView view = messagingService.openConversation(user.getId(), convId, page);
        Page<ConversationRow> conversations = messagingService.listConversations(user.getId(), 0);
        model.addAttribute(ATTR_CONVERSATIONS, conversations);
        model.addAttribute(ATTR_CONVERSATION, view);
        // Keep compose non-null so the template's !compose checks never hit a null.
        model.addAttribute(ATTR_COMPOSE, false);
        // Override the pre-handler badge count now that this conversation is read.
        model.addAttribute(ATTR_MSG_UNREAD, messagingService.unreadCount(user.getId()));
        // Preserve message paging in the pager (bound to this conversation URL).
        Map<String, Object> params = new LinkedHashMap<>();
        model.addAttribute(ATTR_PAGER_PARAMS, params);
        return VIEW_MESSAGING_INDEX;
    }

    /**
     * Sends a message. When called by the {@code fetch} client
     * ({@code X-Requested-With: XMLHttpRequest}) it returns a small JSON body; a
     * plain form submit redirects back to the conversation instead. Validation
     * errors (blank / too long) surface as a {@link ResponseStatusException} the
     * client turns into an inline error while preserving the composer text.
     */
    @PostMapping("/{convId}")
    public Object send(@PathVariable Long convId,
                       @RequestParam("body") String body,
                       @AuthenticationPrincipal UlpUserDetails user,
                       HttpServletRequest request, Model model) {
        boolean ajax = "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
        if (ajax) {
            SendResult result = messagingService.send(user.getId(), convId, body);
            // Wrap in ResponseEntity so Spring serializes JSON; a bare Map return
            // would be treated as model attributes and try to resolve a view (500).
            return ResponseEntity.ok(sendResultBody(result));
        }
        // Non-JS fallback: send, then redirect back to the thread (PRG).
        messagingService.send(user.getId(), convId, body);
        return "redirect:" + BASE_MY_MESSAGES + "/" + convId;
    }

    /**
     * Renders the "new conversation" compose surface: full eligible roster plus a
     * client-side filter box. Server always returns the complete gate-filtered
     * list (no server-side {@code q}); {@code messaging.js} filters by name/email
     * in the browser for quick pick.
     */
    @GetMapping("/new")
    public String compose(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        Page<ConversationRow> conversations = messagingService.listConversations(user.getId(), 0);
        // Full roster — client-side search only (see messaging.js bindComposeFilter).
        List<RecipientRow> recipients = messagingService.searchRecipients(user.getId(), user.getRole(), null);
        model.addAttribute(ATTR_CONVERSATIONS, conversations);
        model.addAttribute(ATTR_RECIPIENTS, recipients);
        model.addAttribute(ATTR_COMPOSE, true);
        model.addAttribute(ATTR_COMPOSE_QUERY, "");
        model.addAttribute(ATTR_PAGER_PARAMS, new LinkedHashMap<String, Object>());
        return VIEW_MESSAGING_INDEX;
    }

    /**
     * Gets or creates a conversation with the target user, then redirects to it.
     * The recipient gate runs in the service; an ineligible target yields a 404
     * so the target's existence is never leaked.
     */
    @PostMapping("/new")
    public String start(@RequestParam("to") Long to,
                        @AuthenticationPrincipal UlpUserDetails user) {
        Long convId = messagingService.getOrCreateConversation(user.getId(), user.getRole(), to);
        return "redirect:" + BASE_MY_MESSAGES + "/" + convId;
    }

    /** Fallback polling endpoint: the caller's current total unread count. */
    @GetMapping("/unread-count")
    @ResponseBody
    public Map<String, Long> unreadCount(@AuthenticationPrincipal UlpUserDetails user) {
        return Map.of("count", messagingService.unreadCount(user.getId()));
    }

    /** Small JSON body for the fetch send path (ok + the stored message data). */
    private static Map<String, Object> sendResultBody(SendResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("messageId", result.messageId());
        data.put("convId", result.convId());
        data.put("body", result.body());
        data.put("createdAt", result.createdAt() == null ? null : result.createdAt().toString());
        data.put("peerUnread", result.peerUnread());
        return data;
    }
}
