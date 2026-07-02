package com.ulp.features.classes.controller;

import com.ulp.features.classes.dto.ProgressDtos.StudentBreakdown;
import com.ulp.features.classes.service.LecturerProgressBreakdownService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON endpoint for the per-student progress drill-down
 * (lecturer-student-progress).
 *
 * <p>{@code GET /lecturer/classes/{id}/progress/{studentId}/lessons} returns the
 * class's PUBLISHED lessons grouped by section, each annotated with the target
 * student's status. Class-level {@code @PreAuthorize} blocks STUDENT / anonymous
 * (403); ownership + membership are enforced in the service. Failures collapse to
 * the shared {@code AjaxResponses} envelope: 403 for a non-owner, 404 for a
 * missing class or a target that is not an ACTIVE member.
 */
@RestController
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassProgressApiController {

    private final LecturerProgressBreakdownService breakdownService;

    public ClassProgressApiController(LecturerProgressBreakdownService breakdownService) {
        this.breakdownService = breakdownService;
    }

    @GetMapping(value = "/lecturer/classes/{id}/progress/{studentId}/lessons",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> breakdown(@PathVariable Long id,
                                       @PathVariable Long studentId,
                                       @AuthenticationPrincipal UlpUserDetails user) {
        try {
            StudentBreakdown data = breakdownService.getStudentLessonBreakdown(
                    id, studentId, user.getId(), user.getRole());
            return ResponseEntity.ok(data);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }
}
