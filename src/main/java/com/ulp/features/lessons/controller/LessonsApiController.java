package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.dto.LessonDtos.LessonReorderRequest;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.service.LessonsService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON endpoints for lesson mutations that must update state without a
 * full reload — delete and drag-and-drop reorder.
 *
 * <p>Split out of {@link LessonsController} during the file-size refactor:
 * the view controller now hosts only Thymeleaf-rendering endpoints (create
 * / edit / publish / unpublish), and this class hosts only AJAX/JSON ones.
 * Authorization layering matches the view controller — class-level
 * {@code @PreAuthorize} blocks STUDENT and anonymous; {@link LessonsService}
 * enforces ownership and the section↔class binding.
 */
@RestController
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonsApiController {

    private static final Logger log = LoggerFactory.getLogger(LessonsApiController.class);

    private final LessonsService lessonsService;

    public LessonsApiController(LessonsService lessonsService) {
        this.lessonsService = lessonsService;
    }

    @DeleteMapping(value = "/{lessonId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> deleteLesson(@PathVariable Long classId,
                                                   @PathVariable Long sectionId,
                                                   @PathVariable Long lessonId,
                                                   @AuthenticationPrincipal UlpUserDetails user) {
        try {
            lessonsService.delete(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete lesson {} in class {} / section {}",
                    lessonId, classId, sectionId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/reorder",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> reorderLessons(@PathVariable Long classId,
                                                     @PathVariable Long sectionId,
                                                     @RequestBody(required = false) LessonReorderRequest body,
                                                     @AuthenticationPrincipal UlpUserDetails user) {
        // Authoritative validation lives in LessonsService.reorder — forward
        // the payload (or null) so the user sees a consistent error message
        // regardless of which guard tripped.
        List<Long> orderedIds = body == null ? null : body.orderedIds();
        try {
            lessonsService.reorder(classId, sectionId, orderedIds,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to reorder lessons in class {} / section {}",
                    classId, sectionId, ex);
            return internalError();
        }
    }
}
