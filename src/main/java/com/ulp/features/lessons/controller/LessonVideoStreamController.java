package com.ulp.features.lessons.controller;

import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.upload.LessonVideoStorageService;
import com.ulp.security.Role;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;

/**
 * Streams uploaded MP4 lesson videos to authenticated viewers with HTTP
 * Range support so {@code <video controls>} elements can seek.
 *
 * <p>Authorization mirrors the attachment download endpoint: enrolled
 * student + PUBLISHED lesson, or any lecturer/head/admin who owns the
 * class (DRAFT included). On every failure path we return 404 so the
 * existence of the resource is not leaked.
 */
@RestController
public class LessonVideoStreamController {

    private static final Logger log = LoggerFactory.getLogger(LessonVideoStreamController.class);

    /** Bytes returned per Range request when no end-range is supplied. */
    private static final int DEFAULT_CHUNK = 1024 * 1024;

    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final LessonVideoStorageService videoStorage;
    private final ClassesService classesService;

    public LessonVideoStreamController(LessonRepository lessonRepository,
                                       SectionRepository sectionRepository,
                                       EnrollmentRepository enrollmentRepository,
                                       ClassRepository classRepository,
                                       LessonVideoStorageService videoStorage,
                                       ClassesService classesService) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.videoStorage = videoStorage;
        this.classesService = classesService;
    }

    @GetMapping("/api/lessons/{lessonId}/video/stream")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> stream(@PathVariable Long lessonId,
                                    @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                    HttpServletRequest request,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        Lesson lesson;
        try {
            lesson = lessonRepository.findById(lessonId)
                    .orElseThrow(() -> new EntityNotFoundException("lesson"));
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (!CONTENT_TYPE_VIDEO.equals(lesson.getContentType())
                || !VIDEO_PROVIDER_UPLOAD.equals(lesson.getVideoProvider())
                || lesson.getVideoUrl() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Long classId = resolveClassId(lesson);
        if (classId == null || !canStream(classId, user.getId(), user.getRole(), lesson)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Path absolute;
        try {
            absolute = videoStorage.resolveAbsolutePath(lesson.getVideoUrl());
        } catch (IllegalArgumentException ex) {
            log.warn("Lesson {} stored video path is invalid: {}", lessonId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!Files.exists(absolute)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileSize;
        try {
            fileSize = Files.size(absolute);
        } catch (IOException e) {
            log.error("Failed to stat MP4 for lesson {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        Resource resource = new FileSystemResource(absolute);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .inline().filename("lesson-" + lessonId + ".mp4").build());

        // No Range header → return the whole body as 200 OK.
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(fileSize);
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }

        // Parse the Range header. We honour a single byte range; multipart
        // ranges are not required by HTML5 <video> for seeking.
        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                    .build();
        }
        if (ranges.isEmpty()) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                    .build();
        }
        ResourceRegion region = toRegion(ranges.get(0), resource, fileSize);
        long end = region.getPosition() + region.getCount() - 1;
        headers.add(HttpHeaders.CONTENT_RANGE,
                "bytes " + region.getPosition() + "-" + end + "/" + fileSize);
        headers.setContentLength(region.getCount());
        return new ResponseEntity<>(region, headers, HttpStatus.PARTIAL_CONTENT);
    }

    /** Build a bounded {@link ResourceRegion} from a single byte range. */
    private static ResourceRegion toRegion(HttpRange range, Resource resource, long fileSize) {
        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        long count = Math.min(end - start + 1, DEFAULT_CHUNK);
        return new ResourceRegion(resource, start, count);
    }

    private Long resolveClassId(Lesson lesson) {
        Section section = sectionRepository.findById(lesson.getSectionId()).orElse(null);
        return section == null ? null : section.getClassId();
    }

    /**
     * Lecturer/head/admin of the class always passes; an enrolled student
     * passes only when the lesson is PUBLISHED and the class is still live
     * (not soft-deleted). Any failure returns false so the caller collapses
     * to 404 — existence of the resource is never leaked.
     */
    private boolean canStream(Long classId, Long userId, Role role, Lesson lesson) {
        if (role == Role.LECTURER || role == Role.HEAD || role == Role.ADMIN) {
            try {
                classesService.getEditable(classId, userId, role);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (!LESSON_STATUS_PUBLISHED.equals(lesson.getStatus())) return false;
        // Verify the class is still live — @SQLRestriction filters
        // soft-deleted rows so a missing class collapses to 404 even if a
        // stale enrollment row remains.
        if (classRepository.findById(classId).isEmpty()) return false;
        Optional<Enrollment> e = enrollmentRepository.findByUserIdAndClassId(userId, classId);
        return e.filter(en -> Enrollment.STATUS_ACTIVE.equals(en.getStatus())).isPresent();
    }
}
