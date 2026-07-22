package com.ulp.features.library.controller;

import com.ulp.features.library.dto.LibraryDtos.AttachLessonContentSummary;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetClassesPage;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetLessonRow;
import com.ulp.features.library.dto.LibraryDtos.AttachTargetSectionRow;
import com.ulp.features.library.service.LibraryAttachTargetsService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.DEFAULT_LIBRARY_TARGET_PAGE_SIZE;
import static com.ulp.common.IConstant.PATH_LIBRARY;
import static com.ulp.common.IConstant.PATH_LIBRARY_TARGETS;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON target lists for the library attach-to-class / clone wizards.
 *
 * <p>Routes under {@code /lecturer/library/targets/**}. Section listing may
 * create a default {@code Chương 1} when the class is empty. Write path for
 * binding assets stays on existing lesson bind endpoints.
 */
@RestController
@RequestMapping(BASE_LECTURER + PATH_LIBRARY + PATH_LIBRARY_TARGETS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LibraryAttachTargetsController {

    private static final Logger log = LoggerFactory.getLogger(LibraryAttachTargetsController.class);

    private final LibraryAttachTargetsService targetsService;

    public LibraryAttachTargetsController(LibraryAttachTargetsService targetsService) {
        this.targetsService = targetsService;
    }

    @GetMapping(value = "/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> classes(@RequestParam(name = "q", defaultValue = "") String q,
                                     @RequestParam(name = "page", defaultValue = "0") int page,
                                     @RequestParam(name = "size",
                                             defaultValue = "" + DEFAULT_LIBRARY_TARGET_PAGE_SIZE) int size,
                                     @AuthenticationPrincipal UlpUserDetails user) {
        try {
            AttachTargetClassesPage result = targetsService.listClasses(
                    user.getId(), user.getRole(), q, page, size);
            return ResponseEntity.ok(result);
        } catch (RuntimeException ex) {
            log.error("Failed to list attach target classes for user {}", user.getId(), ex);
            return internalError();
        }
    }

    @GetMapping(value = "/classes/{classId}/sections", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sections(@PathVariable Long classId,
                                      @AuthenticationPrincipal UlpUserDetails user) {
        try {
            List<AttachTargetSectionRow> rows = targetsService.listSections(
                    classId, user.getId(), user.getRole());
            return ResponseEntity.ok(rows);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to list sections for class {} user {}", classId, user.getId(), ex);
            return internalError();
        }
    }

    @GetMapping(value = "/classes/{classId}/sections/{sectionId}/lessons",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> lessons(@PathVariable Long classId,
                                     @PathVariable Long sectionId,
                                     @AuthenticationPrincipal UlpUserDetails user) {
        try {
            List<AttachTargetLessonRow> rows = targetsService.listLessons(
                    classId, sectionId, user.getId(), user.getRole());
            return ResponseEntity.ok(rows);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to list lessons for section {} user {}", sectionId, user.getId(), ex);
            return internalError();
        }
    }

    @GetMapping(value = "/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/content-summary",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> contentSummary(@PathVariable Long classId,
                                            @PathVariable Long sectionId,
                                            @PathVariable Long lessonId,
                                            @AuthenticationPrincipal UlpUserDetails user) {
        try {
            AttachLessonContentSummary summary = targetsService.contentSummary(
                    classId, sectionId, lessonId, user.getId(), user.getRole());
            return ResponseEntity.ok(summary);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed content-summary for lesson {} user {}", lessonId, user.getId(), ex);
            return internalError();
        }
    }
}
