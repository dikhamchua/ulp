package com.ulp.features.comments.controller;

import com.ulp.features.comments.dto.LessonCommentsDtos.CommentListResponse;
import com.ulp.features.comments.dto.LessonCommentsDtos.CommentRow;
import com.ulp.features.comments.dto.LessonCommentsDtos.CreateRequest;
import com.ulp.features.comments.dto.LessonCommentsDtos.EditRequest;
import com.ulp.features.comments.service.LessonCommentsService;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON API for lesson comments (ULP-4.6) under
 * {@code /api/lessons/{lessonId}/comments}.
 *
 * <p>All authorization lives in {@link LessonCommentsService}; this
 * controller only maps exceptions onto the shared {@link AjaxResult}
 * envelope: {@link IllegalArgumentException} → 400,
 * {@link AccessDeniedException} → 403, {@link EntityNotFoundException} → 404,
 * any other {@link RuntimeException} → 500 (logged).
 */
@RestController
@RequestMapping(value = "/api/lessons/{lessonId}/comments",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
public class LessonCommentsApiController {

    private static final Logger log = LoggerFactory.getLogger(LessonCommentsApiController.class);

    private final LessonCommentsService commentsService;

    public LessonCommentsApiController(LessonCommentsService commentsService) {
        this.commentsService = commentsService;
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable Long lessonId,
                                  @AuthenticationPrincipal UlpUserDetails user) {
        try {
            List<CommentRow> rows = commentsService.list(lessonId, user.getId());
            return ResponseEntity.ok(AjaxResult.success(new CommentListResponse(rows)));
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to list comments for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@PathVariable Long lessonId,
                                    @RequestBody CreateRequest req,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            CommentRow row = commentsService.create(
                    lessonId, user.getId(), req.content(), req.parentId());
            return ResponseEntity.ok(AjaxResult.success(row));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to create comment on lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PutMapping(value = "/{commentId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> edit(@PathVariable Long lessonId,
                                  @PathVariable Long commentId,
                                  @RequestBody EditRequest req,
                                  @AuthenticationPrincipal UlpUserDetails user) {
        try {
            CommentRow row = commentsService.editOwn(
                    lessonId, commentId, user.getId(), req.content());
            return ResponseEntity.ok(AjaxResult.success(row));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to edit comment {} on lesson {}", commentId, lessonId, ex);
            return internalError();
        }
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<AjaxResult> delete(@PathVariable Long lessonId,
                                             @PathVariable Long commentId,
                                             @AuthenticationPrincipal UlpUserDetails user) {
        try {
            commentsService.delete(lessonId, commentId, user.getId());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete comment {} on lesson {}", commentId, lessonId, ex);
            return internalError();
        }
    }
}
