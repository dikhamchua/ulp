package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.dto.SectionDtos.ReorderRequest;
import com.ulp.features.lessons.service.SectionsService;
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
 * JSON endpoints for section mutations that must update state without a
 * full reload — delete and drag-and-drop reorder.
 *
 * <p>Split out of {@link SectionsController} during the file-size refactor:
 * the view controller now hosts only Thymeleaf-rendering endpoints, and
 * this class hosts only AJAX/JSON ones. Authorization layering is
 * identical: class-level {@code @PreAuthorize} blocks STUDENT and
 * anonymous; {@link SectionsService} additionally rejects non-owning
 * lecturers.
 */
@RestController
@RequestMapping("/lecturer/classes/{classId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class SectionsApiController {

    private static final Logger log = LoggerFactory.getLogger(SectionsApiController.class);

    private final SectionsService sectionsService;

    public SectionsApiController(SectionsService sectionsService) {
        this.sectionsService = sectionsService;
    }

    /** Soft-deletes a section. Returns JSON so the list can update in-place. */
    @DeleteMapping(value = "/sections/{sectionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> deleteSection(@PathVariable Long classId,
                                                    @PathVariable Long sectionId,
                                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            sectionsService.delete(classId, sectionId, user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete section {} in class {}", sectionId, classId, ex);
            return internalError();
        }
    }

    /** Persists a new order for the class's sections (drag-and-drop API). */
    @PostMapping(value = "/sections/reorder",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AjaxResult> reorderSections(@PathVariable Long classId,
                                                       @RequestBody(required = false) ReorderRequest body,
                                                       @AuthenticationPrincipal UlpUserDetails user) {
        // Authoritative validation lives in SectionsService.reorder — it
        // raises IllegalArgumentException with a user-facing message for
        // every invalid shape (null list, wrong size, duplicate ids,
        // unknown ids). Forward the payload (or null) and let the service
        // own the contract so the user sees a consistent error message
        // regardless of which guard tripped.
        List<Long> orderedIds = body == null ? null : body.orderedIds();
        try {
            sectionsService.reorder(
                    classId, orderedIds, user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to reorder sections in class {}", classId, ex);
            return internalError();
        }
    }
}
