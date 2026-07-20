package com.ulp.features.lessons.controller;

import com.ulp.entities.Lesson;
import com.ulp.entities.LessonAttachment;
import com.ulp.features.lessons.dto.LessonDtos.LessonAttachmentRow;
import com.ulp.features.lessons.dto.LessonDtos.LessonContentSummary;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.service.LessonAttachmentsService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.lessons.support.VimeoEmbedUrl;
import com.ulp.features.lessons.support.YouTubeEmbedUrl;
import com.ulp.features.upload.LessonVideoStorageService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static com.ulp.common.IConstant.MSG_VIDEO_FILE_TOO_LARGE;
import static com.ulp.common.IConstant.MSG_VIDEO_URL_INVALID;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_VIMEO;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_YOUTUBE;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * Out-of-band content endpoints for PDF + video uploads / URL set
 * (add-lesson-content-types, Sprint 3).
 *
 * <p>Three JSON endpoints scoped under
 * {@code /lecturer/classes/{cid}/sections/{sid}/lessons/{lid}/content/}:
 * <ul>
 *   <li>{@code POST /pdf} — multipart PDF upload; binds as main PDF.</li>
 *   <li>{@code POST /video} — multipart MP4 upload; sets video_provider=UPLOAD.</li>
 *   <li>{@code POST /video-url} — form-urlencoded; validates YouTube/Vimeo URL.</li>
 * </ul>
 *
 * <p>The lesson's {@code content_type} stays unchanged until the lecturer
 * saves the edit form; these endpoints just persist the per-type body so
 * the eventual save can switch into PDF/VIDEO without rejection.
 */
@RestController
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons/{lessonId}/content")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonContentApiController {

    private static final Logger log = LoggerFactory.getLogger(LessonContentApiController.class);

    private final LessonAttachmentsService attachmentsService;
    private final LessonVideoStorageService videoStorage;
    private final LessonsService lessonsService;
    private final LessonAttachmentRepository attachmentRepository;

    public LessonContentApiController(LessonAttachmentsService attachmentsService,
                                      LessonVideoStorageService videoStorage,
                                      LessonsService lessonsService,
                                      LessonAttachmentRepository attachmentRepository) {
        this.attachmentsService = attachmentsService;
        this.videoStorage = videoStorage;
        this.lessonsService = lessonsService;
        this.attachmentRepository = attachmentRepository;
    }

    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadPdf(@PathVariable Long classId,
                                       @PathVariable Long sectionId,
                                       @PathVariable Long lessonId,
                                       @RequestParam("file") MultipartFile file,
                                       @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonAttachmentRow row = attachmentsService.uploadMainPdf(classId, sectionId,
                    lessonId, file, user.getId(), user.getRole());
            Lesson lesson = lessonsService.getEditableLesson(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(summary(lesson, row.originalFilename()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (MaxUploadSizeExceededException ex) {
            return badRequest(MSG_VIDEO_FILE_TOO_LARGE);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IOException ex) {
            log.error("Failed to write PDF for lesson {}", lessonId, ex);
            return internalError();
        } catch (RuntimeException ex) {
            log.error("Unexpected error uploading PDF for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/video", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadVideo(@PathVariable Long classId,
                                         @PathVariable Long sectionId,
                                         @PathVariable Long lessonId,
                                         @RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal UlpUserDetails user) {
        try {
            // Ensure the lecturer owns the class + the lesson belongs here
            // BEFORE we touch the filesystem.
            lessonsService.getEditableLesson(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            LessonVideoStorageService.StoredVideo stored = videoStorage.store(file, lessonId);
            Lesson lesson = lessonsService.setUploadedVideo(classId, sectionId, lessonId,
                    stored.storedPath(), user.getId(), user.getRole());
            return ResponseEntity.ok(summary(lesson, null));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (MaxUploadSizeExceededException ex) {
            return badRequest(MSG_VIDEO_FILE_TOO_LARGE);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IOException ex) {
            log.error("Failed to write MP4 for lesson {}", lessonId, ex);
            return internalError();
        } catch (RuntimeException ex) {
            log.error("Unexpected error uploading MP4 for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/pdf-from-library", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bindPdfFromLibrary(@PathVariable Long classId,
                                                @PathVariable Long sectionId,
                                                @PathVariable Long lessonId,
                                                @RequestParam("assetId") Long assetId,
                                                @AuthenticationPrincipal UlpUserDetails user) {
        try {
            LessonAttachmentRow row = attachmentsService.bindPdfFromLibrary(
                    classId, sectionId, lessonId, assetId, user.getId(), user.getRole());
            Lesson lesson = lessonsService.getEditableLesson(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(summary(lesson, row.originalFilename()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to bind library PDF for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/video-from-library", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bindVideoFromLibrary(@PathVariable Long classId,
                                                  @PathVariable Long sectionId,
                                                  @PathVariable Long lessonId,
                                                  @RequestParam("assetId") Long assetId,
                                                  @AuthenticationPrincipal UlpUserDetails user) {
        try {
            Lesson lesson = lessonsService.bindVideoFromLibrary(
                    classId, sectionId, lessonId, assetId, user.getId(), user.getRole());
            return ResponseEntity.ok(summary(lesson, null));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to bind library video for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/video-url", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setVideoUrl(@PathVariable Long classId,
                                         @PathVariable Long sectionId,
                                         @PathVariable Long lessonId,
                                         @RequestParam("provider") String provider,
                                         @RequestParam("url") String url,
                                         @AuthenticationPrincipal UlpUserDetails user) {
        try {
            if (!isValidProviderUrl(provider, url)) {
                return badRequest(MSG_VIDEO_URL_INVALID);
            }
            Lesson lesson = lessonsService.setExternalVideo(classId, sectionId, lessonId,
                    provider, url, user.getId(), user.getRole());
            return ResponseEntity.ok(summary(lesson, null));
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to set video URL for lesson {}", lessonId, ex);
            return internalError();
        }
    }

    /** Whitelist guard: provider name + URL must match the embed helper. */
    private static boolean isValidProviderUrl(String provider, String url) {
        if (provider == null || url == null) return false;
        if (VIDEO_PROVIDER_YOUTUBE.equals(provider)) {
            return YouTubeEmbedUrl.matches(url);
        }
        if (VIDEO_PROVIDER_VIMEO.equals(provider)) {
            return VimeoEmbedUrl.matches(url);
        }
        return false;
    }

    /** Builds the JSON summary returned by all three endpoints. */
    private LessonContentSummary summary(Lesson lesson, String pdfFilename) {
        String filename = pdfFilename;
        if (filename == null && lesson.getPdfAttachmentId() != null) {
            // Fetch the bound row's filename so the client can render the
            // "Đang dùng: x.pdf" line even when the call did not upload.
            LessonAttachment att = attachmentRepository.findById(lesson.getPdfAttachmentId())
                    .orElse(null);
            if (att != null) filename = att.getOriginalFilename();
        }
        return new LessonContentSummary(
                lesson.getContentType(),
                lesson.getPdfAttachmentId(),
                filename,
                lesson.getVideoUrl(),
                lesson.getVideoProvider());
    }
}
