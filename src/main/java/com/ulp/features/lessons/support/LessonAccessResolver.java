package com.ulp.features.lessons.support;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Shared lesson resolution + published-visibility gate for student-facing
 * lesson surfaces (detail, progress, comments).
 *
 * <p>Every failure collapses to a single {@link EntityNotFoundException} with
 * the canonical message so lesson existence is never leaked. This helper owns
 * ONLY the resolution + live-class + PUBLISHED gates; each caller keeps its own
 * access policy (ACTIVE enrollment, or enrolled-or-owning-lecturer) inline.
 */
@Component
public class LessonAccessResolver {

    private static final String NF_MSG = "Class not found or not accessible";

    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;

    public LessonAccessResolver(ClassRepository classRepository,
                                SectionRepository sectionRepository,
                                LessonRepository lessonRepository) {
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
    }

    /** The resolved live class, section and PUBLISHED lesson for a request. */
    public record ResolvedLesson(ClassEntity clazz, Section section, Lesson lesson) { }

    /**
     * Resolves for URL-scoped surfaces (class id + lesson id): verifies the
     * class is live, the lesson exists + is PUBLISHED + not soft-deleted, and
     * its section belongs to the given class.
     *
     * @throws EntityNotFoundException on any failure, always with the canonical
     *         message so existence is never leaked
     */
    public ResolvedLesson resolveInClass(Long classId, Long lessonId) {
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        Section section = sectionRepository.findById(lesson.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        // Cross-class guard: deny if the lesson lives in another class.
        if (!classId.equals(section.getClassId())) {
            throw new EntityNotFoundException(NF_MSG);
        }
        requirePublished(lesson);
        return new ResolvedLesson(clazz, section, lesson);
    }

    /**
     * Resolves for lesson-only surfaces (comments): derives the class from the
     * lesson's section, then verifies the lesson is PUBLISHED and the class is
     * live.
     *
     * @throws EntityNotFoundException on any failure, always with the canonical
     *         message so existence is never leaked
     */
    public ResolvedLesson resolveByLesson(Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        Section section = sectionRepository.findById(lesson.getSectionId())
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        ClassEntity clazz = classRepository.findById(section.getClassId())
                .orElseThrow(() -> new EntityNotFoundException(NF_MSG));
        requirePublished(lesson);
        return new ResolvedLesson(clazz, section, lesson);
    }

    /** DRAFT lessons are lecturer-private — never visible on student surfaces. */
    private void requirePublished(Lesson lesson) {
        if (!Lesson.STATUS_PUBLISHED.equals(lesson.getStatus())) {
            throw new EntityNotFoundException(NF_MSG);
        }
    }
}
