package com.ulp.features.lessons.service;

import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.ulp.common.IConstant.LESSON_STATUS_DRAFT;
import static com.ulp.common.IConstant.LESSON_STATUS_PUBLISHED;
import static com.ulp.common.IConstant.MSG_LESSON_NOT_FOUND;
import static com.ulp.common.IConstant.MSG_SECTION_NOT_FOUND;

/**
 * Publish / unpublish operations for the lessons tab (ULP-4.0b).
 *
 * <p>Pulled out of {@link LessonsService} during the C.3 structural split
 * so the publish state-transition concern is isolated from CRUD and
 * reorder. Authorization rules mirror {@link LessonsService} verbatim:
 * a LECTURER may only publish/unpublish lessons in classes they own,
 * HEAD and ADMIN may do so on any class; the targeted {@code sectionId}
 * must belong to {@code classId}.
 *
 * <p>Each transition is {@code @Transactional} and writes a single audit
 * row through {@link LessonActivityWriter}. Calling {@code publish} on an
 * already-published lesson (or {@code unpublish} on a draft) is a no-op:
 * no entity mutation, no audit write.
 */
@Service
public class LessonsPublishService {

    private final LessonRepository lessonRepository;
    private final SectionRepository sectionRepository;
    private final ClassesService classesService;
    private final LessonActivityWriter activityWriter;

    public LessonsPublishService(LessonRepository lessonRepository,
                                 SectionRepository sectionRepository,
                                 ClassesService classesService,
                                 LessonActivityWriter activityWriter) {
        this.lessonRepository = lessonRepository;
        this.sectionRepository = sectionRepository;
        this.classesService = classesService;
        this.activityWriter = activityWriter;
    }

    /** Publishes a lesson and writes a PUBLISHED activity. No-op when already published. */
    @Transactional
    public void publish(Long classId, Long sectionId, Long lessonId,
                        Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        if (LESSON_STATUS_PUBLISHED.equals(lesson.getStatus())) return;
        lesson.publish();
        lessonRepository.save(lesson);
        activityWriter.write(
                lesson.getId(),
                LessonActivity.TYPE_PUBLISHED,
                "Xuất bản bài giảng " + lesson.getTitle(),
                userId);
    }

    /** Reverts a lesson to DRAFT and writes an UNPUBLISHED activity. No-op when already a draft. */
    @Transactional
    public void unpublish(Long classId, Long sectionId, Long lessonId,
                          Long userId, Role role) {
        classesService.getEditable(classId, userId, role);
        verifySectionBelongsToClass(sectionId, classId);
        Lesson lesson = loadLesson(sectionId, lessonId);
        if (LESSON_STATUS_DRAFT.equals(lesson.getStatus())) return;
        lesson.unpublish();
        lessonRepository.save(lesson);
        activityWriter.write(
                lesson.getId(),
                LessonActivity.TYPE_UNPUBLISHED,
                "Chuyển bài giảng " + lesson.getTitle() + " về nháp",
                userId);
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Verifies that the section exists and lives inside the requested class.
     * Throws {@link EntityNotFoundException} otherwise — surfaced as 404 by
     * the controller. The class-scoped lookup blocks path-variable
     * enumeration attempts (e.g. POSTing class A's URL with section B's id).
     */
    private void verifySectionBelongsToClass(Long sectionId, Long classId) {
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
        // Side-effect-only check; the result is discarded but referenced so
        // the compiler does not warn about an unused local.
        if (section.getId() == null) {
            throw new IllegalStateException("Section id missing after lookup");
        }
    }

    private Lesson loadLesson(Long sectionId, Long lessonId) {
        return lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
    }
}
