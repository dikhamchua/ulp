package com.ulp.features.classes.service.support;

import com.ulp.features.assignments.repository.AssignmentRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.lessons.repository.LessonAttachmentRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Batch-loads the four stat columns shown on the lecturer class list card.
 *
 * <p>Issues at most four grouped COUNT queries for the page's class ids
 * (students, lessons, assignments, attachments). Missing classes default to 0.
 */
@Component
public class ClassListStatsLoader {

    /** Zero stats for classes with no related rows. */
    public static final Stats ZERO = new Stats(0, 0, 0, 0);

    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final AssignmentRepository assignmentRepository;
    private final LessonAttachmentRepository lessonAttachmentRepository;

    public ClassListStatsLoader(EnrollmentRepository enrollmentRepository,
                                LessonRepository lessonRepository,
                                AssignmentRepository assignmentRepository,
                                LessonAttachmentRepository lessonAttachmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.assignmentRepository = assignmentRepository;
        this.lessonAttachmentRepository = lessonAttachmentRepository;
    }

    /**
     * Loads per-class stats for the given ids.
     *
     * @param classIds class ids on the current list page; empty → empty map
     * @return map keyed by class id; callers should {@code getOrDefault(id, ZERO)}
     */
    public Map<Long, Stats> load(Collection<Long> classIds) {
        if (classIds == null || classIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Integer> students = toCountMap(
                enrollmentRepository.countActiveGroupedByClassIds(classIds),
                EnrollmentRepository.ClassCount::getClassId,
                EnrollmentRepository.ClassCount::getCnt);
        Map<Long, Integer> lectures = toCountMap(
                lessonRepository.countAllGroupedByClassIds(classIds),
                LessonRepository.ClassCount::getClassId,
                LessonRepository.ClassCount::getCnt);
        Map<Long, Integer> assignments = toCountMap(
                assignmentRepository.countNotDeletedGroupedByClassIds(classIds),
                AssignmentRepository.ClassCount::getClassId,
                AssignmentRepository.ClassCount::getCnt);
        Map<Long, Integer> materials = toCountMap(
                lessonAttachmentRepository.countGroupedByClassIds(classIds),
                LessonAttachmentRepository.ClassCount::getClassId,
                LessonAttachmentRepository.ClassCount::getCnt);

        Map<Long, Stats> out = new HashMap<>(classIds.size());
        for (Long classId : classIds) {
            if (classId == null) {
                continue;
            }
            out.put(classId, new Stats(
                    students.getOrDefault(classId, 0),
                    lectures.getOrDefault(classId, 0),
                    assignments.getOrDefault(classId, 0),
                    materials.getOrDefault(classId, 0)));
        }
        return out;
    }

    private static <T> Map<Long, Integer> toCountMap(Collection<T> rows,
                                                     Function<T, Long> classIdFn,
                                                     Function<T, Long> cntFn) {
        Map<Long, Integer> map = new HashMap<>();
        for (T row : rows) {
            Long classId = classIdFn.apply(row);
            Long cnt = cntFn.apply(row);
            if (classId != null) {
                map.put(classId, cnt == null ? 0 : cnt.intValue());
            }
        }
        return map;
    }

    /**
     * Four list-card counters for one class.
     *
     * @param studentCount    ACTIVE enrollments
     * @param lectureCount    non-soft-deleted lessons (includes DRAFT)
     * @param assignmentCount non-deleted assignments (includes DRAFT)
     * @param materialCount   lesson attachments under the class's lessons
     */
    public record Stats(int studentCount, int lectureCount, int assignmentCount, int materialCount) {
    }
}
