package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.dto.StudentLessonsDtos.StudentLessonRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.CONTENT_TYPE_RICHTEXT;

/**
 * Read service backing {@code GET /my/classes/{classId}/lessons}.
 *
 * <p>Enforces two authz rules in this order:
 * <ol>
 *   <li>The caller MUST have an enrollment row for {@code classId} with
 *       status {@code ACTIVE}. REMOVED / COMPLETED / missing → 404 to
 *       avoid leaking class existence (see design D5, D6).</li>
 *   <li>The class itself must be live (not soft-deleted). The repo's
 *       {@code @SQLRestriction} filters soft-deleted rows
 *       transparently — a missing row maps to 404.</li>
 * </ol>
 *
 * <p>Visibility filter (design D3): only {@link Lesson#STATUS_PUBLISHED}
 * lessons reach the view model. DRAFT lessons are stripped at this
 * layer so any future consumer (mobile API, etc.) inherits the rule.
 */
@Service
public class StudentLessonsService {

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;

    public StudentLessonsService(EnrollmentRepository enrollmentRepository,
                                 ClassRepository classRepository,
                                 SectionRepository sectionRepository,
                                 LessonRepository lessonRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
    }

    /**
     * Returns the lesson view for the given class scoped to an
     * ACTIVE-enrolled student.
     *
     * @param classId target class id
     * @param userId  authenticated user id
     * @return populated {@link ClassLessonsView}; sections list is empty
     *         when the class has none, individual section lesson lists
     *         may be empty when nothing is PUBLISHED yet
     * @throws EntityNotFoundException when the caller is not
     *         ACTIVE-enrolled or the class is soft-deleted / missing
     */
    @Transactional(readOnly = true)
    public ClassLessonsView listClassLessons(Long classId, Long userId) {
        // Gate 1: enrollment must be ACTIVE — REMOVED/COMPLETED → 404.
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));

        // Gate 2: class must be live. @SQLRestriction filters soft-deletes.
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Class not found or not accessible"));

        List<Section> sections = sectionRepository
                .findByClassIdOrderByDisplayOrderAsc(classId);

        List<SectionWithLessons> sectionRows = new ArrayList<>(sections.size());
        for (Section section : sections) {
            sectionRows.add(buildSectionRow(section));
        }
        return new ClassLessonsView(clazz.getId(), clazz.getName(), sectionRows);
    }

    /** Builds a sidebar row + its PUBLISHED-only lesson list. */
    private SectionWithLessons buildSectionRow(Section section) {
        // Repo returns live lessons (SQLRestriction); we still filter
        // by status so DRAFT never reaches the view.
        List<Lesson> rawLessons = lessonRepository
                .findBySectionIdOrderByDisplayOrderAsc(section.getId());

        List<StudentLessonRow> lessons = new ArrayList<>(rawLessons.size());
        for (Lesson l : rawLessons) {
            if (Lesson.STATUS_PUBLISHED.equals(l.getStatus())) {
                // Legacy rows pre-V16 may carry null content_type — default
                // to RICHTEXT so the right-rail card still picks an icon.
                String type = l.getContentType() == null
                        ? CONTENT_TYPE_RICHTEXT : l.getContentType();
                lessons.add(new StudentLessonRow(
                        l.getId(), l.getTitle(),
                        l.getSectionId(), l.getPublishedAt(), type));
            }
        }

        short order = section.getDisplayOrder() == null
                ? 0 : section.getDisplayOrder();
        return new SectionWithLessons(
                section.getId(), section.getTitle(), order, lessons);
    }
}
