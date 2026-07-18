package com.ulp.features.lecturer.service.support;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.repository.EnrollmentRepository.ClassCount;
import com.ulp.features.classes.repository.EnrollmentRepository.ClassUserId;
import com.ulp.features.classes.service.support.ProgressMath;
import com.ulp.features.lecturer.dto.LecturerDashboardDtos.ClassDashboardRow;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.LessonRepository.ClassLessonId;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.features.progress.repository.LearningProgressRepository.UserLessonId;
import com.ulp.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.DEFAULT_TEACHING_PAGE_SIZE;
import static com.ulp.common.IConstant.MAX_TEACHING_PAGE_SIZE;

/**
 * Batch loaders + table window helpers for {@code LecturerDashboardService}.
 * Kept separate so the orchestration service stays under the ~200-line guideline.
 */
@Component
public class LecturerDashboardQuerySupport {

    private final ClassRepository classRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final LearningProgressRepository progressRepository;

    public LecturerDashboardQuerySupport(ClassRepository classRepository,
                                         EnrollmentRepository enrollmentRepository,
                                         LessonRepository lessonRepository,
                                         LearningProgressRepository progressRepository) {
        this.classRepository = classRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
    }

    /** Scope mirrors {@code ClassesService#listForUser}. */
    public List<ClassEntity> loadScopedClasses(Long userId, Role role) {
        if (role == Role.LECTURER) {
            return classRepository.findAllByLecturerIdOrderByCreatedAtDesc(userId);
        }
        return classRepository.findAllByOrderByCreatedAtDesc();
    }

    public Map<Long, Long> loadActiveStudentCounts(List<Long> classIds) {
        Map<Long, Long> map = new HashMap<>();
        for (ClassCount row : enrollmentRepository.countActiveGroupedByClassIds(classIds)) {
            map.put(row.getClassId(), row.getCnt() == null ? 0L : row.getCnt());
        }
        return map;
    }

    public Map<Long, List<Long>> loadPublishedLessonIds(List<Long> classIds) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (ClassLessonId row : lessonRepository.findPublishedLessonIdsByClassIds(classIds)) {
            map.computeIfAbsent(row.getClassId(), k -> new ArrayList<>()).add(row.getLessonId());
        }
        return map;
    }

    public Map<Long, List<Long>> loadActiveStudentIds(List<Long> classIds) {
        Map<Long, List<Long>> map = new HashMap<>();
        for (ClassUserId row : enrollmentRepository.findActiveUserIdsByClassIds(classIds)) {
            map.computeIfAbsent(row.getClassId(), k -> new ArrayList<>()).add(row.getUserId());
        }
        return map;
    }

    /**
     * Completed lesson sets keyed by user. Empty when no published lessons exist
     * (avoids invalid empty {@code IN ()} SQL).
     */
    public Map<Long, Set<Long>> loadCompletedLessonSets(Map<Long, List<Long>> lessonIdsByClass) {
        List<Long> allLessonIds = new ArrayList<>();
        for (List<Long> ids : lessonIdsByClass.values()) {
            allLessonIds.addAll(ids);
        }
        if (allLessonIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> completed = new HashMap<>();
        for (UserLessonId pair : progressRepository.findCompletedUserLessonPairs(allLessonIds)) {
            completed.computeIfAbsent(pair.getUserId(), k -> new HashSet<>())
                    .add(pair.getLessonId());
        }
        return completed;
    }

    /**
     * Mean of each ACTIVE student's completion % in the class.
     * Zero students or zero published lessons → 0.
     */
    public int classAveragePercent(List<Long> studentIds,
                                   List<Long> publishedLessonIds,
                                   Map<Long, Set<Long>> completedByUser) {
        if (studentIds.isEmpty() || publishedLessonIds.isEmpty()) {
            return 0;
        }
        Set<Long> publishedSet = new HashSet<>(publishedLessonIds);
        int total = publishedLessonIds.size();
        int sum = 0;
        for (Long studentId : studentIds) {
            Set<Long> completed = completedByUser.getOrDefault(studentId, Set.of());
            int completedCount = 0;
            for (Long lessonId : completed) {
                if (publishedSet.contains(lessonId)) {
                    completedCount++;
                }
            }
            sum += ProgressMath.percent(completedCount, total);
        }
        return (int) Math.round((double) sum / studentIds.size());
    }

    /** Case-insensitive substring match on class name or code. */
    public List<ClassDashboardRow> filterByQuery(List<ClassDashboardRow> rows, String q) {
        if (q == null || q.isBlank()) {
            return rows;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<ClassDashboardRow> out = new ArrayList<>();
        for (ClassDashboardRow row : rows) {
            String name = row.name() == null ? "" : row.name().toLowerCase(Locale.ROOT);
            String code = row.code() == null ? "" : row.code().toLowerCase(Locale.ROOT);
            if (name.contains(needle) || code.contains(needle)) {
                out.add(row);
            }
        }
        return out;
    }

    /** Clamps page/size and returns a Spring {@link Page} window. */
    public Page<ClassDashboardRow> paginate(List<ClassDashboardRow> rows, int page, int size) {
        int safeSize = size <= 0 ? DEFAULT_TEACHING_PAGE_SIZE
                : Math.min(size, MAX_TEACHING_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int total = rows.size();
        // Clamp absurd offsets to an empty last window (no int overflow).
        long offset = (long) safePage * safeSize;
        int from = (int) Math.min(offset, total);
        int to = Math.min(from + safeSize, total);
        return new PageImpl<>(new ArrayList<>(rows.subList(from, to)),
                PageRequest.of(safePage, safeSize), total);
    }
}
