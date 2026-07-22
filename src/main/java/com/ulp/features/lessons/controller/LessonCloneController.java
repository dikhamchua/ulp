package com.ulp.features.lessons.controller;

import com.ulp.features.library.dto.LibraryDtos.LessonCloneResult;
import com.ulp.features.library.dto.LibraryDtos.LessonTemplateRow;
import com.ulp.features.library.service.LessonTemplateService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.MSG_GENERIC_RETRY;
import static com.ulp.common.IConstant.MSG_TEMPLATE_CLONE_OK;
import static com.ulp.common.IConstant.MSG_TEMPLATE_SAVE_FAILED;
import static com.ulp.common.IConstant.MSG_TEMPLATE_SAVED;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonEditUrl;
import static com.ulp.features.lessons.controller.support.LessonFormSupport.lessonsTabUrl;

/**
 * Save-to-template and clone-to-class actions on an existing lesson.
 *
 * <p>Form POSTs flash + redirect for toolbar buttons; JSON clone supports the
 * in-page wizard without leaving the edit form.
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonCloneController {

    private static final Logger log = LoggerFactory.getLogger(LessonCloneController.class);

    private final LessonTemplateService templateService;

    public LessonCloneController(LessonTemplateService templateService) {
        this.templateService = templateService;
    }

    /** Saves the lesson into the lecturer's personal template library. */
    @PostMapping("/save-template")
    public String saveTemplate(@PathVariable Long classId,
                               @PathVariable Long sectionId,
                               @PathVariable Long lessonId,
                               @AuthenticationPrincipal UlpUserDetails user,
                               RedirectAttributes ra) {
        try {
            LessonTemplateRow row = templateService.saveFromLesson(
                    classId, sectionId, lessonId, user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_TEMPLATE_SAVED + ": " + row.title());
        } catch (AccessDeniedException | EntityNotFoundException
                 | IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to save lesson {} as template for user {}",
                    lessonId, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_TEMPLATE_SAVE_FAILED);
        }
        return "redirect:" + lessonEditUrl(classId, sectionId, lessonId);
    }

    /**
     * Clones this lesson into another editable class section as DRAFT.
     * JSON for the clone wizard on the edit form / lessons list.
     */
    @PostMapping(value = "/clone", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> cloneLesson(@PathVariable Long classId,
                                         @PathVariable Long sectionId,
                                         @PathVariable Long lessonId,
                                         @RequestParam("targetClassId") Long targetClassId,
                                         @RequestParam("targetSectionId") Long targetSectionId,
                                         @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonCloneResult result = templateService.cloneLessonToSection(
                    classId, sectionId, lessonId,
                    targetClassId, targetSectionId,
                    user.getId(), user.getRole());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", MSG_TEMPLATE_CLONE_OK);
            body.put("lessonId", result.lessonId());
            body.put("classId", result.classId());
            body.put("sectionId", result.sectionId());
            body.put("title", result.title());
            body.put("editUrl", lessonEditUrl(
                    result.classId(), result.sectionId(), result.lessonId()));
            return ResponseEntity.ok(body);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to clone lesson {} for user {}", lessonId, user.getId(), ex);
            return internalError();
        }
    }

    /** Form fallback clone (non-AJAX) — redirects to destination edit page. */
    @PostMapping("/clone-form")
    public String cloneLessonForm(@PathVariable Long classId,
                                  @PathVariable Long sectionId,
                                  @PathVariable Long lessonId,
                                  @RequestParam("targetClassId") Long targetClassId,
                                  @RequestParam("targetSectionId") Long targetSectionId,
                                  @AuthenticationPrincipal UlpUserDetails user,
                                  RedirectAttributes ra) {
        try {
            LessonCloneResult result = templateService.cloneLessonToSection(
                    classId, sectionId, lessonId,
                    targetClassId, targetSectionId,
                    user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_TEMPLATE_CLONE_OK);
            return "redirect:" + lessonEditUrl(
                    result.classId(), result.sectionId(), result.lessonId());
        } catch (AccessDeniedException | EntityNotFoundException
                 | IllegalArgumentException | IllegalStateException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed form-clone lesson {} for user {}", lessonId, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }
}
