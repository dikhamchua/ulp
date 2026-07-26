package com.ulp.features.lessons.controller;

import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonAttachmentsService.DownloadHandle;
import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageNotConfiguredException;
import com.ulp.features.storage.StoredObject;
import com.ulp.features.storage.StoredObjectResource;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.charset.StandardCharsets;

import static com.ulp.common.IConstant.MSG_ATTACHMENT_TOO_LARGE;
import static com.ulp.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;
import static com.ulp.common.IConstant.MSG_STORAGE_UPLOAD_FAILED;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * JSON / streaming endpoints for lesson attachments (ULP-4.0c).
 */
@RestController
public class LessonAttachmentsApiController {

    private static final Logger log = LoggerFactory.getLogger(LessonAttachmentsApiController.class);

    private final LessonAttachmentsService attachmentsService;
    private final ObjectStorage objectStorage;

    public LessonAttachmentsApiController(LessonAttachmentsService attachmentsService,
                                          ObjectStorage objectStorage) {
        this.attachmentsService = attachmentsService;
        this.objectStorage = objectStorage;
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
        } catch (StorageNotConfiguredException ex) {
            return badRequest(MSG_STORAGE_R2_NOT_CONFIGURED);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IOException ex) {
            log.error("Failed to write attachment for lesson {}", lessonId, ex);
            return badRequest(MSG_STORAGE_UPLOAD_FAILED);
        } catch (RuntimeException ex) {
            log.error("Unexpected error uploading attachment to lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/attachments/from-library",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
    public ResponseEntity<?> bindFromLibrary(@PathVariable Long classId,
                                             @PathVariable Long sectionId,
                                             @PathVariable Long lessonId,
                                             @RequestParam("assetId") Long assetId,
                                             @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonAttachmentRow row = attachmentsService.bindAttachmentFromLibrary(
                    classId, sectionId, lessonId, assetId, user.getId(), user.getRole());
            return ResponseEntity.ok(row);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to bind library attachment to lesson {}", lessonId, ex);
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
        StoredObject obj;
        try {
            if (!objectStorage.exists(handle.storageKey())) {
                log.warn("Attachment row {} points at missing object {}", attachmentId, handle.storageKey());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            obj = objectStorage.open(handle.storageKey());
        } catch (IOException ex) {
            log.error("Failed to read attachment object {}", handle.storageKey(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFilename(handle.originalFilename()), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(parseMime(handle.mimeType()));
        long length = obj.contentLength() >= 0 ? obj.contentLength() : handle.sizeBytes();
        if (length >= 0) {
            headers.setContentLength(length);
        }
        return new ResponseEntity<>(new StoredObjectResource(obj, handle.storageKey()),
                headers, HttpStatus.OK);
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
