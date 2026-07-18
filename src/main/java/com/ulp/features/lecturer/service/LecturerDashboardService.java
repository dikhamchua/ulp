package com.ulp.features.lecturer.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.lecturer.dto.LecturerDashboardDtos.ClassDashboardRow;
import com.ulp.features.lecturer.dto.LecturerDashboardDtos.TeachingDashboardView;
import com.ulp.features.lecturer.dto.LecturerDashboardDtos.TeachingStats;
import com.ulp.features.lecturer.service.support.LecturerDashboardQuerySupport;
import com.ulp.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates teaching KPIs and per-class rows for the lecturer dashboard.
 *
 * <p>Authorization mirrors {@code ClassesService#listForUser}: LECTURER sees
 * only owned classes; HEAD/ADMIN see all non-deleted classes. KPI cards are
 * computed over the FULL scoped cohort; the table is searched and paginated
 * afterwards (same pattern as {@code LecturerProgressService}).
 */
@Service
public class LecturerDashboardService {

    private static final String CLASS_STATUS_ACTIVE = "ACTIVE";

    private final LecturerDashboardQuerySupport querySupport;

    public LecturerDashboardService(LecturerDashboardQuerySupport querySupport) {
        this.querySupport = querySupport;
    }

    /**
     * Builds the teaching dashboard for the given caller.
     *
     * @param userId current user id
     * @param role   current user role (LECTURER/HEAD/ADMIN)
     * @param q      free-text search over class name / code (may be blank)
     * @param page   zero-based page index
     * @param size   page size (clamped)
     * @return KPI stats (full scope) plus searched/paginated class rows
     */
    @Transactional(readOnly = true)
    public TeachingDashboardView getDashboard(Long userId, Role role,
                                              String q, int page, int size) {
        List<ClassEntity> classes = querySupport.loadScopedClasses(userId, role);
        if (classes.isEmpty()) {
            return TeachingDashboardView.empty();
        }

        List<Long> classIds = classes.stream().map(ClassEntity::getId).toList();
        Map<Long, Long> studentCounts = querySupport.loadActiveStudentCounts(classIds);
        Map<Long, List<Long>> lessonIdsByClass = querySupport.loadPublishedLessonIds(classIds);
        Map<Long, List<Long>> studentIdsByClass = querySupport.loadActiveStudentIds(classIds);
        Map<Long, Set<Long>> completedByUser =
                querySupport.loadCompletedLessonSets(lessonIdsByClass);

        List<ClassDashboardRow> allRows = new ArrayList<>(classes.size());
        long totalStudents = 0L;
        long activeClasses = 0L;
        int avgSum = 0;

        for (ClassEntity clazz : classes) {
            Long classId = clazz.getId();
            long students = studentCounts.getOrDefault(classId, 0L);
            totalStudents += students;
            if (CLASS_STATUS_ACTIVE.equals(clazz.getStatus())) {
                activeClasses++;
            }

            int classAvg = querySupport.classAveragePercent(
                    studentIdsByClass.getOrDefault(classId, List.of()),
                    lessonIdsByClass.getOrDefault(classId, List.of()),
                    completedByUser);
            avgSum += classAvg;

            allRows.add(new ClassDashboardRow(
                    classId,
                    clazz.getName(),
                    clazz.getCode(),
                    clazz.getStatus(),
                    students,
                    classAvg));
        }

        int overallAvg = (int) Math.round((double) avgSum / classes.size());
        TeachingStats stats = new TeachingStats(
                classes.size(), totalStudents, activeClasses, overallAvg);

        // KPI uses full cohort; table window applies search + page after.
        List<ClassDashboardRow> filtered = querySupport.filterByQuery(allRows, q);
        Page<ClassDashboardRow> pageObj = querySupport.paginate(filtered, page, size);
        return new TeachingDashboardView(stats, pageObj);
    }
}
