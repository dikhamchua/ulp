package com.ulp.features.messaging.controller;

import com.ulp.features.messaging.dto.MessagingDtos.ClassMessagesView;
import com.ulp.features.messaging.service.MessagingService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;

import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_VIEW;
import static com.ulp.common.IConstant.VIEW_STUDENT_CLASS_MESSAGES;

/**
 * Student-facing SSR controller for the class-scoped messaging page under
 * {@code /my/classes/{classId}/messages}. Mirrors the class lessons/tests shell:
 * the shared class sidebar on the left, the conversation with that class's
 * lecturer in the main pane, so the student never leaves the class context.
 *
 * <p>Access is gated in {@link MessagingService#openClassConversation} — the
 * caller must be ACTIVE-enrolled in the class, else 404 (existence never leaked).
 */
@Controller
@RequestMapping("/my/classes/{classId}/messages")
@PreAuthorize("isAuthenticated()")
public class StudentClassMessagesController {

    private final MessagingService messagingService;

    public StudentClassMessagesController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /** Opens the student's thread with the class lecturer, inside the class shell. */
    @GetMapping
    public String open(@PathVariable Long classId,
                       @RequestParam(name = "page", defaultValue = "-1") int page,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        ClassMessagesView view = messagingService.openClassConversation(user.getId(), classId, page);
        model.addAttribute(ATTR_VIEW, view);
        // Message pager (older pages) posts back to the global conversation URL.
        model.addAttribute(ATTR_PAGER_PARAMS, new LinkedHashMap<String, Object>());
        return VIEW_STUDENT_CLASS_MESSAGES;
    }
}
