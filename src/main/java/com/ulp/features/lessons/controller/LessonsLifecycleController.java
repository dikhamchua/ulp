package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.controller.support.MutationFailureHandler;
import com.ulp.features.lessons.service.LessonsPublishService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.MSG_LESSON_PUBLISHED;
import static com.ulp.common.IConstant.MSG_LESSON_UNPUBLISHED;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonsTabUrl;

/**
 * Publish / unpublish form-POST endpoints for lessons.
 *
 * <p>Split out of {@link LessonsController} during the file-size refactor;
 * shares the same authorization layering — class-level
 * {@code @PreAuthorize} blocks STUDENT / anonymous, and the service
 * enforces class ownership + the section↔class binding.
 *
 * <p>Both endpoints are form-POST + redirect so the lessons tab refreshes
 * after the state transition with a flash toast.
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonsLifecycleController {

    private static final Logger log = LoggerFactory.getLogger(LessonsLifecycleController.class);

    private static final String MSG_LESSON_PUBLISH_FAILED = "Xuất bản bài giảng thất bại, vui lòng thử lại.";

    private final LessonsPublishService publishService;

    public LessonsLifecycleController(LessonsPublishService publishService) {
        this.publishService = publishService;
    }

    @PostMapping("/{lessonId}/publish")
    public String publishLesson(@PathVariable Long classId,
                                @PathVariable Long sectionId,
                                @PathVariable Long lessonId,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        try {
            publishService.publish(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_PUBLISHED);
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_PUBLISH_FAILED, log,
                    "Failed to publish lesson " + lessonId + " in class " + classId);
        }
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }

    @PostMapping("/{lessonId}/unpublish")
    public String unpublishLesson(@PathVariable Long classId,
                                  @PathVariable Long sectionId,
                                  @PathVariable Long lessonId,
                                  @AuthenticationPrincipal UlpUserDetails user,
                                  RedirectAttributes ra) {
        try {
            publishService.unpublish(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_UNPUBLISHED);
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_PUBLISH_FAILED, log,
                    "Failed to unpublish lesson " + lessonId + " in class " + classId);
        }
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }
}
