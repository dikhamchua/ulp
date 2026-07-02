package com.ulp.features.progress.controller;

import com.ulp.features.progress.service.LearningProgressService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.MSG_PROGRESS_MARKED_COMPLETE;
import static com.ulp.common.IConstant.MSG_PROGRESS_MARKED_INCOMPLETE;

/**
 * Handles the student "mark lesson complete" toggle (ULP-4.5).
 *
 * <p>POST-Redirect-Get: the toggle reloads the lessons page so every
 * aggregate (section counts, class percent, rail badges) refreshes at once.
 * All authz gates live in {@link LearningProgressService}; any gate failure
 * raises {@code EntityNotFoundException} mapped to HTTP 404 by the global
 * handler, so the endpoint never leaks lesson existence.
 */
@Controller
@RequestMapping("/my/classes/{classId}/lessons/{lessonId}/progress")
@PreAuthorize("isAuthenticated()")
public class LearningProgressController {

    private final LearningProgressService progressService;

    public LearningProgressController(LearningProgressService progressService) {
        this.progressService = progressService;
    }

    /**
     * Toggles completion then redirects back to the inlined lesson.
     *
     * @param section the active section id, echoed so the redirect
     *                pre-selects the correct sidebar entry
     */
    @PostMapping("/toggle")
    public String toggle(@PathVariable Long classId,
                         @PathVariable Long lessonId,
                         @RequestParam("section") Long section,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        boolean nowCompleted = progressService.toggleCompletion(classId, lessonId, user.getId());
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS,
                nowCompleted ? MSG_PROGRESS_MARKED_COMPLETE : MSG_PROGRESS_MARKED_INCOMPLETE);
        return "redirect:" + lessonUrl(classId, section, lessonId);
    }

    /** Builds the canonical inlined-lesson URL; carries path/query variables. */
    private static String lessonUrl(Long classId, Long sectionId, Long lessonId) {
        return "/my/classes/" + classId + "/lessons"
                + "?section=" + sectionId + "&lesson=" + lessonId;
    }
}
