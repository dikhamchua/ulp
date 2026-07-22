package com.ulp.features.library.controller;

import com.ulp.features.library.dto.LibraryDtos.LessonCloneResult;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonsPageView;
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
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
import static com.ulp.common.IConstant.ATTR_LIBRARY_CLASS_ID;
import static com.ulp.common.IConstant.ATTR_LIBRARY_CLASS_OPTIONS;
import static com.ulp.common.IConstant.ATTR_LIBRARY_DOCUMENT_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_KIND;
import static com.ulp.common.IConstant.ATTR_LIBRARY_PAGE;
import static com.ulp.common.IConstant.ATTR_LIBRARY_QUERY;
import static com.ulp.common.IConstant.ATTR_LIBRARY_SIZE;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TAB;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TEMPLATE_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TOTAL_COUNT;
import static com.ulp.common.IConstant.ATTR_LIBRARY_VIDEO_COUNT;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.DEFAULT_LIBRARY_PAGE_SIZE;
import static com.ulp.common.IConstant.LIBRARY_TAB_TEMPLATES;
import static com.ulp.common.IConstant.MSG_GENERIC_RETRY;
import static com.ulp.common.IConstant.MSG_TEMPLATE_CLONE_OK;
import static com.ulp.common.IConstant.MSG_TEMPLATE_DELETED;
import static com.ulp.common.IConstant.MSG_TEMPLATE_RENAMED;
import static com.ulp.common.IConstant.PATH_LIBRARY;
import static com.ulp.common.IConstant.URL_LIBRARY;
import static com.ulp.common.IConstant.VIEW_LIBRARY;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * SSR + JSON for the library "Bài giảng" rail: live lessons across classes
 * plus template clone / rename / delete endpoints.
 *
 * <p>{@code GET /lecturer/library/templates} lists the lecturer's lessons.
 * Clone is JSON so the wizard can finish without a full page reload.
 */
@Controller
@RequestMapping(BASE_LECTURER + PATH_LIBRARY + "/templates")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonTemplateController {

    private static final Logger log = LoggerFactory.getLogger(LessonTemplateController.class);
    private static final String REDIRECT_TEMPLATES =
            "redirect:" + URL_LIBRARY + "?tab=" + LIBRARY_TAB_TEMPLATES;

    private final LessonTemplateService templateService;

    public LessonTemplateController(LessonTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public String page(@RequestParam(name = "q", defaultValue = "") String q,
                       @RequestParam(name = "classId", required = false) Long classId,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "size",
                               defaultValue = "" + DEFAULT_LIBRARY_PAGE_SIZE) int size,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        // Primary rail: every lesson in classes this lecturer owns (optional class filter).
        LibraryLessonsPageView view = templateService.listLessons(
                user.getId(), q, classId, page, size);
        model.addAttribute(ATTR_LIBRARY_PAGE, view.page());
        model.addAttribute(ATTR_LIBRARY_QUERY, view.q());
        model.addAttribute(ATTR_LIBRARY_KIND, "");
        model.addAttribute(ATTR_LIBRARY_TAB, LIBRARY_TAB_TEMPLATES);
        model.addAttribute(ATTR_LIBRARY_SIZE, view.page().getSize());
        model.addAttribute(ATTR_LIBRARY_CLASS_ID, view.classId());
        model.addAttribute(ATTR_LIBRARY_CLASS_OPTIONS, view.classOptions());
        model.addAttribute(ATTR_LIBRARY_TOTAL_COUNT, view.totalCount());
        model.addAttribute(ATTR_LIBRARY_DOCUMENT_COUNT, view.documentCount());
        model.addAttribute(ATTR_LIBRARY_VIDEO_COUNT, view.videoCount());
        // Sidebar badge = live lesson count (what the rail actually lists).
        model.addAttribute(ATTR_LIBRARY_TEMPLATE_COUNT, view.lessonCount());
        model.addAttribute(ATTR_PAGER_PARAMS,
                pagerParams(view.q(), view.classId(), view.page().getSize()));
        return VIEW_LIBRARY;
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable Long id,
                         @RequestParam("title") String title,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            templateService.rename(user.getId(), id, title);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_TEMPLATE_RENAMED);
        } catch (IllegalArgumentException | EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to rename template {} for user {}", id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT_TEMPLATES;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @AuthenticationPrincipal UlpUserDetails user,
                         RedirectAttributes ra) {
        try {
            templateService.softDelete(user.getId(), id);
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_TEMPLATE_DELETED);
        } catch (EntityNotFoundException ex) {
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete template {} for user {}", id, user.getId(), ex);
            ra.addFlashAttribute(ATTR_FLASH_ERROR, MSG_GENERIC_RETRY);
        }
        return REDIRECT_TEMPLATES;
    }

    /**
     * Clones a template into an editable class section (DRAFT). JSON body for
     * the library clone wizard.
     */
    @PostMapping(value = "/{id}/clone", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> cloneTemplate(@PathVariable Long id,
                                           @RequestParam("classId") Long classId,
                                           @RequestParam("sectionId") Long sectionId,
                                           @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonCloneResult result = templateService.cloneTemplateToSection(
                    id, classId, sectionId, user.getId(), user.getRole());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", MSG_TEMPLATE_CLONE_OK);
            body.put("lessonId", result.lessonId());
            body.put("classId", result.classId());
            body.put("sectionId", result.sectionId());
            body.put("title", result.title());
            return ResponseEntity.ok(body);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to clone template {} for user {}", id, user.getId(), ex);
            return internalError();
        }
    }

    private static Map<String, Object> pagerParams(String q, Long classId, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tab", LIBRARY_TAB_TEMPLATES);
        if (q != null && !q.isBlank()) params.put("q", q.trim());
        if (classId != null) params.put("classId", classId);
        params.put("size", size);
        return params;
    }
}
