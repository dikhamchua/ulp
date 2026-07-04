package com.ulp.features.tests.controller;

import com.ulp.features.tests.dto.TestDtos.SubmitRequest;
import com.ulp.features.tests.dto.TestDtos.SubmitResult;
import com.ulp.features.tests.service.TestAttemptService;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.ulp.common.IConstant.API_TESTS;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * JSON API for the student exam-taking flow under {@code /api/tests}: submit
 * (all answers at once, graded server-side with authoritative deadline
 * enforcement) and the lightweight heartbeat that feeds live monitoring.
 *
 * <p>Authorization lives in the service / {@code TestAccessResolver}; this
 * controller only maps exceptions onto the shared {@link AjaxResult} envelope.
 */
@RestController
@RequestMapping(value = API_TESTS, produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
public class TestApiController {

    private static final Logger log = LoggerFactory.getLogger(TestApiController.class);

    private final TestAttemptService attemptService;

    public TestApiController(TestAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    /** Submits all answers for an attempt; grades + closes it (owner-only). */
    @PostMapping(value = "/attempts/{attemptId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@PathVariable Long attemptId,
                                    @RequestBody SubmitRequest request,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            SubmitResult result = attemptService.submit(attemptId, user.getId(), request);
            return ResponseEntity.ok(AjaxResult.success(result));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to submit attempt {}", attemptId, ex);
            return internalError();
        }
    }

    /** Records a heartbeat (updates last_activity_at) for a live attempt. */
    @PostMapping("/attempts/{attemptId}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable Long attemptId,
                                       @AuthenticationPrincipal UlpUserDetails user) {
        try {
            attemptService.heartbeat(attemptId, user.getId());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed heartbeat for attempt {}", attemptId, ex);
            return internalError();
        }
    }
}
