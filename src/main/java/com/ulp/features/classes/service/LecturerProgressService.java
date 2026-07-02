package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.classes.dto.ProgressDtos.ProgressPageView;
import com.ulp.features.classes.dto.ProgressDtos.ProgressSummary;
import com.ulp.features.classes.dto.ProgressDtos.StudentProgressRow;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.support.AvatarStyles;
import com.ulp.features.classes.service.support.ProgressMath;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.progress.repository.LearningProgressRepository;
import com.ulp.features.progress.repository.LearningProgressRepository.ProgressAggregate;
import com.ulp.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.DEFAULT_PROGRESS_PAGE_SIZE;

/**
 * Read service for the lecturer progress dashboard table + summary cards
 * (lecturer-student-progress). The per-student drill-down lives in
 * {@link LecturerProgressBreakdownService} to keep concerns (and files) small.
 *
 * <p>Aggregation strategy (design D2): two bounded queries — the class's
 * published lesson ids and one grouped aggregate over {@code learning_progress}
 * — then in-memory shaping. Summary cards are computed over the FULL cohort; the
 * table is searched / filtered / paginated afterwards. No query-per-student.
 *
 * <p>Authorization reuses {@link ClassesService#getViewable}.
 */
@Service
public class LecturerProgressService {

    private final ClassesService classesService;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final LearningProgressRepository progressRepository;

    public LecturerProgressService(ClassesService classesService,
                                   EnrollmentRepository enrollmentRepository,
                                   LessonRepository lessonRepository,
                                   LearningProgressRepository progressRepository) {
        this.classesService = classesService;
        this.enrollmentRepository = enrollmentRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
    }

    /**
     * Builds the progress dashboard for one class: cohort summary plus a
     * searched / filtered / paginated student table.
     *
     * @throws jakarta.persistence.EntityNotFoundException when the class is
     *         missing / soft-deleted
     * @throws org.springframework.security.access.AccessDeniedException when the
     *         caller may not view the class
     */
    @Transactional(readOnly = true)
    public ProgressPageView getProgressPage(Long classId, Long userId, Role role,
                                            String status, String q, int page, int size) {
        ClassEntity clazz = classesService.getViewable(classId, userId, role);

        List<Long> publishedIds = lessonRepository.findPublishedLessonIdsByClassId(classId);
        int total = publishedIds.size();
        Map<Long, ProgressAggregate> aggByUser = loadAggregate(publishedIds);

        List<Enrollment> enrollments = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(classId, Enrollment.STATUS_ACTIVE);

        // Full cohort rows first — summary must ignore search/filter/page.
        List<StudentProgressRow> allRows = new ArrayList<>(enrollments.size());
        for (int i = 0; i < enrollments.size(); i++) {
            allRows.add(toRow(enrollments.get(i), i, total, aggByUser));
        }
        ProgressSummary summary = summarize(allRows);

        List<StudentProgressRow> filtered = filter(allRows, status, q);
        Page<StudentProgressRow> pageObj = paginate(filtered, page, size);
        return new ProgressPageView(clazz, summary, pageObj);
    }

    // ── Internals ──────────────────────────────────────────────────────

    private Map<Long, ProgressAggregate> loadAggregate(List<Long> publishedIds) {
        if (publishedIds.isEmpty()) return Map.of(); // empty IN () is invalid SQL
        Map<Long, ProgressAggregate> map = new HashMap<>();
        for (ProgressAggregate a : progressRepository.aggregateByLessonIds(publishedIds)) {
            map.put(a.getUserId(), a);
        }
        return map;
    }

    private StudentProgressRow toRow(Enrollment e, int index, int total,
                                     Map<Long, ProgressAggregate> aggByUser) {
        User u = e.getUser();
        ProgressAggregate agg = aggByUser.get(u.getId());
        int completed = agg == null || agg.getCompletedCount() == null
                ? 0 : agg.getCompletedCount().intValue();
        LocalDateTime lastActivity = agg == null ? null : agg.getLastActivity();
        int percent = ProgressMath.percent(completed, total);
        // A non-null lastActivity means the student opened at least one lesson,
        // so "opened but 0 completed" buckets as in-progress, not not-started.
        String bucket = ProgressMath.bucket(completed, total, lastActivity != null);
        return new StudentProgressRow(
                u.getId(), u.getFullName(), u.getEmail(),
                AvatarStyles.label(u.getFullName()), AvatarStyles.gradient(index),
                completed, total, percent, e.getJoinedAt(), lastActivity, bucket);
    }

    private ProgressSummary summarize(List<StudentProgressRow> rows) {
        if (rows.isEmpty()) return new ProgressSummary(0, 0, 0, 0);
        int sum = 0;
        int notStarted = 0;
        int completed = 0;
        for (StudentProgressRow r : rows) {
            sum += r.percent();
            if (ProgressMath.BUCKET_NOT_STARTED.equals(r.status())) notStarted++;
            if (ProgressMath.BUCKET_COMPLETED.equals(r.status())) completed++;
        }
        int avg = (int) Math.round((double) sum / rows.size());
        return new ProgressSummary(rows.size(), avg, notStarted, completed);
    }

    private List<StudentProgressRow> filter(List<StudentProgressRow> rows,
                                            String status, String q) {
        String term = q == null ? "" : q.trim().toLowerCase();
        boolean byStatus = status != null && !ProgressMath.BUCKET_ALL.equals(status);
        List<StudentProgressRow> out = new ArrayList<>();
        for (StudentProgressRow r : rows) {
            if (byStatus && !status.equals(r.status())) continue;
            if (!term.isEmpty() && !matches(r, term)) continue;
            out.add(r);
        }
        return out;
    }

    private boolean matches(StudentProgressRow r, String term) {
        String name = r.fullName() == null ? "" : r.fullName().toLowerCase();
        String email = r.email() == null ? "" : r.email().toLowerCase();
        return name.contains(term) || email.contains(term);
    }

    private Page<StudentProgressRow> paginate(List<StudentProgressRow> rows,
                                              int page, int size) {
        int safeSize = size <= 0 ? DEFAULT_PROGRESS_PAGE_SIZE : size;
        int safePage = Math.max(page, 0);
        int total = rows.size();
        // Compute the offset in long so an absurd page never overflows int
        // into a negative subList index — clamp to an empty last page instead.
        long offset = (long) safePage * safeSize;
        int from = (int) Math.min(offset, total);
        int to = Math.min(from + safeSize, total);
        return new PageImpl<>(new ArrayList<>(rows.subList(from, to)),
                PageRequest.of(safePage, safeSize), total);
    }
}
