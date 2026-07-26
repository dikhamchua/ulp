package com.ulp.features.lessons.controller;

import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.LibraryAsset;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.library.repository.LibraryAssetRepository;
import com.ulp.features.storage.ObjectStorage;
import com.ulp.features.storage.StorageKeys;
import com.ulp.features.storage.StoredObject;
import com.ulp.features.storage.StoredObjectResource;
import com.ulp.security.Role;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Optional;

import static com.ulp.common.IConstant.CONTENT_TYPE_VIDEO;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.VIDEO_PROVIDER_UPLOAD;

/**
 * Streams uploaded MP4 lesson videos to authenticated viewers with HTTP
 * Range support so {@code <video controls>} elements can seek.
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
    private final ObjectStorage objectStorage;
    private final LibraryAssetRepository libraryAssetRepository;
    private final ClassesService classesService;

    public LessonVideoStreamController(LessonRepository lessonRepository,
                                       SectionRepository sectionRepository,
                                       EnrollmentRepository enrollmentRepository,
                                       ClassRepository classRepository,
                                       ObjectStorage objectStorage,
                                       LibraryAssetRepository libraryAssetRepository,
                                       ClassesService classesService) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.objectStorage = objectStorage;
        this.libraryAssetRepository = libraryAssetRepository;
        this.classesService = classesService;
    }

    @GetMapping("/api/lessons/{lessonId}/video/stream")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> stream(@PathVariable Long lessonId,
                                    @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
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
                || (lesson.getVideoUrl() == null && !lesson.hasLibraryVideo())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Long classId = resolveClassId(lesson);
        if (classId == null || !canStream(classId, user.getId(), user.getRole(), lesson)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String key;
        try {
            key = resolveVideoKey(lesson);
        } catch (IllegalArgumentException ex) {
            log.warn("Lesson {} stored video key is invalid: {}", lessonId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (!objectStorage.exists(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileSize;
        try (StoredObject probe = objectStorage.open(key)) {
            fileSize = probe.contentLength();
            if (fileSize < 0) {
                // Fallback: drain is not acceptable for large videos — treat as unknown.
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } catch (IOException e) {
            log.error("Failed to stat MP4 for lesson {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .inline().filename("lesson-" + lessonId + ".mp4").build());

        // No Range header → return the whole body as 200 OK.
        if (rangeHeader == null || rangeHeader.isBlank()) {
            try {
                StoredObject full = objectStorage.open(key);
                headers.setContentLength(fileSize);
                return new ResponseEntity<>(new StoredObjectResource(full, key), headers, HttpStatus.OK);
            } catch (IOException e) {
                log.error("Failed to open MP4 for lesson {}", lessonId, e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }

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

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        // Cap chunk size so a single request does not pull the whole file.
        long count = Math.min(end - start + 1, DEFAULT_CHUNK);
        long cappedEnd = start + count - 1;

        try {
            StoredObject partial = objectStorage.openRange(key, start, cappedEnd);
            headers.add(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + cappedEnd + "/" + fileSize);
            headers.setContentLength(count);
            return new ResponseEntity<>(new StoredObjectResource(partial, key),
                    headers, HttpStatus.PARTIAL_CONTENT);
        } catch (IOException e) {
            log.error("Failed to open ranged MP4 for lesson {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Prefer library asset FK when set; otherwise use the classic lesson
     * video_url key under lessons/.
     */
    private String resolveVideoKey(Lesson lesson) {
        if (lesson.hasLibraryVideo()) {
            LibraryAsset asset = libraryAssetRepository.findById(lesson.getVideoLibraryAssetId())
                    .orElseThrow(() -> new IllegalArgumentException("library video missing"));
            return StorageKeys.requireSafeKey(asset.getStoredPath());
        }
        return StorageKeys.requireSafeKey(lesson.getVideoUrl());
    }

    private Long resolveClassId(Lesson lesson) {
        Section section = sectionRepository.findById(lesson.getSectionId()).orElse(null);
        return section == null ? null : section.getClassId();
    }

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
        if (classRepository.findById(classId).isEmpty()) return false;
        Optional<Enrollment> e = enrollmentRepository.findByUserIdAndClassId(userId, classId);
        return e.filter(en -> Enrollment.STATUS_ACTIVE.equals(en.getStatus())).isPresent();
    }
}
