package com.ulp.features.student.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.support.ProgressMath;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.dto.StudentLessonsDtos.StudentLessonRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final UserRepository userRepository;
    private final LearningProgressRepository progressRepository;

    public StudentLessonsService(EnrollmentRepository enrollmentRepository,
                                 ClassRepository classRepository,
                                 SectionRepository sectionRepository,
                                 LessonRepository lessonRepository,
                                 UserRepository userRepository,
                                 LearningProgressRepository progressRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.userRepository = userRepository;
        this.progressRepository = progressRepository;
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

        // First pass: collect each section's PUBLISHED lessons so we know the
        // full published-id set before a single progress query.
        List<Section> orderedSections = new ArrayList<>(sections);
        List<List<Lesson>> publishedPerSection = new ArrayList<>(sections.size());
        List<Long> publishedIds = new ArrayList<>();
        for (Section section : orderedSections) {
            List<Lesson> published = publishedLessonsOf(section);
            publishedPerSection.add(published);
            for (Lesson l : published) {
                publishedIds.add(l.getId());
            }
        }

        // Single progress query for the whole page — avoids N+1 (design D4).
        Set<Long> completedIds = publishedIds.isEmpty()
                ? Set.of()
                : new HashSet<>(progressRepository
                        .findCompletedLessonIds(userId, publishedIds));

        List<SectionWithLessons> sectionRows = new ArrayList<>(orderedSections.size());
        int completedTotal = 0;
        for (int i = 0; i < orderedSections.size(); i++) {
            SectionWithLessons row = buildSectionRow(
                    orderedSections.get(i), publishedPerSection.get(i), completedIds);
            completedTotal += row.completedCount();
            sectionRows.add(row);
        }

        int publishedTotal = publishedIds.size();
        // Single source of the percent formula (shared with the lecturer view).
        int percent = ProgressMath.percent(completedTotal, publishedTotal);

        // Resolve lecturer name for the sidebar; a deleted lecturer maps to
        // null so the template renders a graceful fallback.
        String lecturerName = userRepository.findById(clazz.getLecturerId())
                .map(User::getFullName)
                .orElse(null);

        return new ClassLessonsView(clazz.getId(), clazz.getName(),
                clazz.getCode(), lecturerName, sectionRows,
                completedTotal, publishedTotal, percent);
    }

    /** Returns a section's live, PUBLISHED lessons in display order. */
    private List<Lesson> publishedLessonsOf(Section section) {
        return lessonRepository.findBySectionIdAndStatusOrderByDisplayOrderAsc(
                section.getId(), Lesson.STATUS_PUBLISHED);
    }

    /** Builds a sidebar row + its PUBLISHED-only lesson list with progress flags. */
    private SectionWithLessons buildSectionRow(Section section,
                                               List<Lesson> published,
                                               Set<Long> completedIds) {
        List<StudentLessonRow> lessons = new ArrayList<>(published.size());
        int completedCount = 0;
        for (Lesson l : published) {
            // Legacy rows pre-V16 may carry null content_type — default
            // to RICHTEXT so the right-rail card still picks an icon.
            String type = l.getContentType() == null
                    ? CONTENT_TYPE_RICHTEXT : l.getContentType();
            boolean completed = completedIds.contains(l.getId());
            if (completed) completedCount++;
            lessons.add(new StudentLessonRow(
                    l.getId(), l.getTitle(),
                    l.getSectionId(), l.getPublishedAt(), type, completed));
        }

        short order = section.getDisplayOrder() == null
                ? 0 : section.getDisplayOrder();
        return new SectionWithLessons(
                section.getId(), section.getTitle(), order, lessons, completedCount);
    }
}
