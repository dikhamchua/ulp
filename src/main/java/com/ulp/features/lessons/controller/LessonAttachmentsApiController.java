package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonAttachmentsService.DownloadHandle;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

import static com.ulp.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON / streaming endpoints for lesson attachments (ULP-4.0c).
 *
 * <p>Split from {@link LessonsApiController} per design D6: keeps both files
 * under the ~200-line guideline and isolates the file-IO concerns from the
 * lesson CRUD JSON endpoints.
 *
 * <p>Two URL spaces share this controller:
 * <ul>
 *   <li>{@code /lecturer/classes/.../attachments[/{id}]} — upload + delete,
 *       restricted to lecturers/heads/admins by the class-level
 *       {@code @PreAuthorize}.</li>
 *   <li>{@code /api/lessons/{lessonId}/attachments/{attachmentId}/download}
 *       — accessible to any authenticated user; service-layer auth gates
 *       students to enrolled + PUBLISHED lessons only.</li>
 * </ul>
 */
@RestController
public class LessonAttachmentsApiController {

    private static final Logger log = LoggerFactory.getLogger(LessonAttachmentsApiController.class);

    private final LessonAttachmentsService attachmentsService;

    public LessonAttachmentsApiController(LessonAttachmentsService attachmentsService) {
        this.attachmentsService = attachmentsService;
    }

    @PostMapping(value = "/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/attachments",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
    public ResponseEntity<?> upload(@PathVariable Long classId,
                                    @PathVariable Long sectionId,
                                    @PathVariable Long lessonId,
                                    @RequestParam("file") MultipartFile file,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonAttachmentRow row = attachmentsService.upload(classId, sectionId, lessonId,
                    file, user.getId(), user.getRole());
            return ResponseEntity.ok(row);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (MaxUploadSizeExceededException ex) {
            return badRequest(MSG_ATTACHMENT_TOO_LARGE);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IOException ex) {
            log.error("Failed to write attachment for lesson {}", lessonId, ex);
            return internalError();
        } catch (RuntimeException ex) {
            log.error("Unexpected error uploading attachment to lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @DeleteMapping(value = "/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/attachments/{attachmentId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
    public ResponseEntity<AjaxResult> delete(@PathVariable Long classId,
                                             @PathVariable Long sectionId,
                                             @PathVariable Long lessonId,
                                             @PathVariable Long attachmentId,
                                             @AuthenticationPrincipal UlpUserDetails user) {
        try {
            attachmentsService.delete(classId, sectionId, lessonId, attachmentId,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete attachment {} on lesson {}", attachmentId, lessonId, ex);
            return internalError();
        }
    }

    @GetMapping("/api/lessons/{lessonId}/attachments/{attachmentId}/download")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Resource> download(@PathVariable Long lessonId,
                                             @PathVariable Long attachmentId,
                                             @AuthenticationPrincipal UlpUserDetails user) {
        DownloadHandle handle;
        try {
            handle = attachmentsService.download(lessonId, attachmentId,
                    user.getId(), user.getRole());
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        InputStream in;
        try {
            in = Files.newInputStream(handle.absolutePath());
        } catch (NoSuchFileException ex) {
            log.warn("Attachment row {} points at missing file {}", attachmentId, handle.absolutePath());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (IOException ex) {
            log.error("Failed to read attachment file {}", handle.absolutePath(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(parseMime(handle.mimeType()));
        headers.setContentLength(handle.sizeBytes());
        return new ResponseEntity<>(new InputStreamResource(in), headers, HttpStatus.OK);
    }

    private static String safeFilename(String name) {
        return name == null || name.isBlank() ? "attachment" : name;
    }

    private static MediaType parseMime(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
