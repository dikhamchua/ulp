package com.ulp.features.classes.service;

import com.ulp.entities.Enrollment;
import com.ulp.entities.LearningProgress;
import com.ulp.entities.Lesson;
import com.ulp.entities.Section;
import com.ulp.features.classes.dto.ProgressDtos.LessonProgressRow;
import com.ulp.features.classes.dto.ProgressDtos.SectionProgressGroup;
import com.ulp.features.classes.dto.ProgressDtos.StudentBreakdown;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.MSG_STUDENT_NOT_IN_CLASS;

/**
 * Read service for the per-student progress drill-down
 * (lecturer-student-progress). Split from {@link LecturerProgressService} so the
 * table-aggregation and drill-down concerns stay in small, focused files.
 *
 * <p>Authorization reuses {@link ClassesService#getViewable}; the target must
 * additionally be an ACTIVE member of the class.
 */
@Service
public class LecturerProgressBreakdownService {

    private final ClassesService classesService;
    private final EnrollmentRepository enrollmentRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final LearningProgressRepository progressRepository;

    public LecturerProgressBreakdownService(ClassesService classesService,
                                            EnrollmentRepository enrollmentRepository,
                                            SectionRepository sectionRepository,
                                            LessonRepository lessonRepository,
                                            LearningProgressRepository progressRepository) {
        this.classesService = classesService;
        this.enrollmentRepository = enrollmentRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
    }

    /**
     * Returns the per-lesson breakdown for one student, grouped by section in
     * display order. Only sections with at least one published lesson appear.
     *
     * @throws EntityNotFoundException when the target is not an ACTIVE member
     * @throws org.springframework.security.access.AccessDeniedException when the
     *         caller may not view the class
     */
    @Transactional(readOnly = true)
    public StudentBreakdown getStudentLessonBreakdown(Long classId, Long studentId,
                                                      Long userId, Role role) {
        classesService.getViewable(classId, userId, role);
        enrollmentRepository.findByUserIdAndClassId(studentId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException(MSG_STUDENT_NOT_IN_CLASS));

        List<Section> sections = sectionRepository.findByClassIdOrderByDisplayOrderAsc(classId);
        List<List<Lesson>> publishedPerSection = new ArrayList<>(sections.size());
        List<Long> publishedIds = new ArrayList<>();
        for (Section section : sections) {
            List<Lesson> published = publishedLessonsOf(section);
            publishedPerSection.add(published);
            for (Lesson l : published) {
                publishedIds.add(l.getId());
            }
        }

        Map<Long, String> statusByLesson = publishedIds.isEmpty()
                ? Map.of()
                : buildStatusMap(studentId, publishedIds);

        List<SectionProgressGroup> groups = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            List<Lesson> published = publishedPerSection.get(i);
            if (published.isEmpty()) continue; // skip empty sections in the drill-down
            List<LessonProgressRow> rows = new ArrayList<>(published.size());
            for (Lesson l : published) {
                String st = statusByLesson.getOrDefault(l.getId(),
                        LearningProgress.STATUS_NOT_STARTED);
                rows.add(new LessonProgressRow(l.getTitle(), st));
            }
            groups.add(new SectionProgressGroup(sections.get(i).getTitle(), rows));
        }
        return new StudentBreakdown(groups);
    }

    // ── Internals ──────────────────────────────────────────────────────

    private Map<Long, String> buildStatusMap(Long studentId, List<Long> publishedIds) {
        Map<Long, String> map = new HashMap<>();
        for (LearningProgress lp : progressRepository
                .findByUserIdAndLessonIdIn(studentId, publishedIds)) {
            map.put(lp.getLessonId(), lp.getStatus());
        }
        return map;
    }

    /** Returns a section's live, PUBLISHED lessons in display order. */
    private List<Lesson> publishedLessonsOf(Section section) {
        return lessonRepository.findBySectionIdAndStatusOrderByDisplayOrderAsc(
                section.getId(), Lesson.STATUS_PUBLISHED);
    }
}
