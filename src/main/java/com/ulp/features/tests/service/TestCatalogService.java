package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.tests.dto.TestDtos.ClassTestsView;
import com.ulp.features.tests.dto.TestDtos.ExamListRow;
import com.ulp.features.tests.dto.TestDtos.StudentExamList;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.features.tests.support.TestAccessQueries;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;

/** Builds the student exam list (accessible exams + attempt summary), paginated. */
@Service
public class TestCatalogService {

    private final TestAccessQueries accessQueries;
    private final TestAttemptRepository attemptRepository;
    private final ClassRepository classRepository;
    private final TestRepository testRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;

    public TestCatalogService(TestAccessQueries accessQueries,
                              TestAttemptRepository attemptRepository,
                              ClassRepository classRepository,
                              TestRepository testRepository,
                              EnrollmentRepository enrollmentRepository,
                              UserRepository userRepository) {
        this.accessQueries = accessQueries;
        this.attemptRepository = attemptRepository;
        this.classRepository = classRepository;
        this.testRepository = testRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
    }

    /**
     * One SSR page of the student's accessible exams, newest-first, each carrying
     * the student's last attempt status and best score percentage. Only the exams
     * paginate (manual {@link PageImpl}); the pager navigates by {@code ?page=N}.
     */
    @Transactional(readOnly = true)
    public StudentExamList listForStudent(Long userId, int page) {
        List<Test> exams = accessQueries.accessibleExams(userId);
        Map<Long, String> classNames = resolveClassNames(exams);
        Map<Long, List<TestAttempt>> attemptsByTest = attemptsByTest(userId, exams);

        List<ExamListRow> rows = new ArrayList<>();
        for (Test t : exams) {
            List<TestAttempt> attempts = attemptsByTest.getOrDefault(t.getId(), List.of());
            rows.add(toRow(t, classNames.get(t.getClassId()), attempts));
        }

        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_EXAM_PAGE_SIZE);
        int from = Math.min(safePage * DEFAULT_EXAM_PAGE_SIZE, rows.size());
        int to = Math.min(from + DEFAULT_EXAM_PAGE_SIZE, rows.size());
        Page<ExamListRow> examsPage = new PageImpl<>(rows.subList(from, to), pageable, rows.size());
        return new StudentExamList(examsPage);
    }

    /**
     * One SSR page of a single class's PUBLISHED exams, title-filtered, for the
     * class-scoped student tests page ({@code GET /my/classes/{classId}/tests}).
     * Gates on ACTIVE enrollment first (same 404-no-leak policy as lessons), then
     * returns class metadata for the shared sidebar plus the paginated rows.
     */
    @Transactional(readOnly = true)
    public ClassTestsView listClassTests(Long classId, Long userId, String query, int page) {
        // Gate 1 — enrollment must be ACTIVE (else 404, no existence leak).
        enrollmentRepository.findByUserIdAndClassId(userId, classId)
                .filter(e -> Enrollment.STATUS_ACTIVE.equals(e.getStatus()))
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp hoặc bạn không có quyền truy cập"));
        // Gate 2 — class must be live (soft-deletes filtered by @SQLRestriction).
        ClassEntity clazz = classRepository.findById(classId)
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy lớp hoặc bạn không có quyền truy cập"));

        String normalized = query == null ? "" : query.trim();
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_EXAM_PAGE_SIZE);
        Page<Test> examPage = testRepository
                .findByClassIdAndStatusAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(
                        classId, Test.STATUS_PUBLISHED, normalized, pageable);

        Map<Long, List<TestAttempt>> attemptsByTest = attemptsByTest(userId, examPage.getContent());
        Page<ExamListRow> rows = examPage.map(t -> toRow(t, clazz.getName(),
                attemptsByTest.getOrDefault(t.getId(), List.of())));

        String lecturerName = userRepository.findById(clazz.getLecturerId())
                .map(User::getFullName).orElse(null);
        return new ClassTestsView(clazz.getId(), clazz.getName(), clazz.getCode(),
                lecturerName, normalized, rows);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ExamListRow toRow(Test t, String className, List<TestAttempt> attempts) {
        String lastStatus = attempts.isEmpty() ? null : attempts.get(0).getStatus();
        Integer bestPercent = bestScorePercent(attempts);
        return new ExamListRow(t.getId(), t.getTitle(), t.getType(), t.getStatus(), className,
                t.isPractice(), t.getDurationMinutes(), t.getTimeMode(), t.getEndAt(),
                t.getTotalQuestions() == null ? 0 : t.getTotalQuestions(),
                lastStatus, bestPercent);
    }

    /** Best score percentage across submitted attempts, or null if none submitted. */
    private Integer bestScorePercent(List<TestAttempt> attempts) {
        Integer best = null;
        for (TestAttempt a : attempts) {
            if (a.isInProgress() || a.getScore() == null || a.getTotalPoints() == null
                    || a.getTotalPoints().signum() <= 0) {
                continue;
            }
            int pct = a.getScore().multiply(BigDecimal.valueOf(100))
                    .divide(a.getTotalPoints(), 0, RoundingMode.HALF_UP).intValue();
            if (best == null || pct > best) best = pct;
        }
        return best;
    }

    private Map<Long, List<TestAttempt>> attemptsByTest(Long userId, List<Test> exams) {
        Map<Long, List<TestAttempt>> map = new HashMap<>();
        if (exams.isEmpty()) return map;
        List<Long> testIds = exams.stream().map(Test::getId).toList();
        for (TestAttempt a : attemptRepository.findByUserIdAndTestIdIn(userId, testIds)) {
            map.computeIfAbsent(a.getTestId(), k -> new ArrayList<>()).add(a);
        }
        // Newest-first so index 0 is the last attempt.
        map.values().forEach(list -> list.sort((x, y) -> y.getStartedAt().compareTo(x.getStartedAt())));
        return map;
    }

    private Map<Long, String> resolveClassNames(List<Test> exams) {
        Map<Long, String> names = new HashMap<>();
        List<Long> classIds = exams.stream().map(Test::getClassId)
                .filter(id -> id != null).distinct().toList();
        if (classIds.isEmpty()) return names;
        for (ClassEntity c : classRepository.findAllById(classIds)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }
}
