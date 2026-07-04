package com.ulp.features.tests.service;

import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamHeader;
import com.ulp.features.tests.dto.LecturerTestDtos.MonitorSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.MonitorStudentRow;
import com.ulp.features.tests.dto.LecturerTestDtos.SubmissionRow;
import com.ulp.features.tests.dto.LecturerTestDtos.SubmissionsView;
import com.ulp.features.tests.dto.TestActivityRow;
import com.ulp.features.tests.dto.TestDtos.ReviewView;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.entity.TestAttempt;
import com.ulp.features.tests.repository.TestActivityRepository;
import com.ulp.features.tests.repository.TestAttemptRepository;
import com.ulp.features.tests.support.TestAccessResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.DEFAULT_SUBMISSIONS_PAGE_SIZE;

/** Live monitor snapshot + submissions overview for an exam the lecturer owns. */
@Service
public class ExamMonitorService {

    /** An IN_PROGRESS attempt is "active now" if its last heartbeat is within this window. */
    private static final long ACTIVE_WINDOW_SECONDS = 60L;

    private final TestAttemptRepository attemptRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final TestAccessResolver accessResolver;
    private final AttemptResultBuilder resultBuilder;
    private final TestActivityRepository activityRepository;

    public ExamMonitorService(TestAttemptRepository attemptRepository,
                              EnrollmentRepository enrollmentRepository,
                              UserRepository userRepository,
                              TestAccessResolver accessResolver,
                              AttemptResultBuilder resultBuilder,
                              TestActivityRepository activityRepository) {
        this.attemptRepository = attemptRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userRepository = userRepository;
        this.accessResolver = accessResolver;
        this.resultBuilder = resultBuilder;
        this.activityRepository = activityRepository;
    }

    /**
     * Lecturer-side per-attempt review for an exam they own (reuses the shared
     * review builder). Ownership + attempt/test pairing are enforced by
     * {@link TestAccessResolver#requireAttemptForManageable}.
     */
    @Transactional(readOnly = true)
    public ReviewView lecturerReview(Long testId, Long attemptId, Long userId) {
        Test test = accessResolver.requireManageable(testId, userId);
        TestAttempt attempt = accessResolver.requireAttemptForManageable(testId, attemptId, userId);
        String studentName = userRepository.findById(attempt.getUserId())
                .map(User::getFullName).orElse("?");
        return resultBuilder.buildReview(test, attempt, true, studentName);
    }

    /** Owner-checked exam header for the monitor/submissions page chrome. */
    @Transactional(readOnly = true)
    public ExamHeader header(Long testId, Long userId) {
        Test test = accessResolver.requireManageable(testId, userId);
        return new ExamHeader(test.getId(), test.getTitle(), test.getStatus(),
                test.getTimeMode(), test.getEndAt(), test.getTotalQuestions());
    }

    /** Owner-checked paged audit history for the exam's "Lịch sử" tab (newest first). */
    @Transactional(readOnly = true)
    public Page<TestActivityRow> historyFor(Long testId, Long userId, Pageable pageable) {
        accessResolver.requireManageable(testId, userId);
        return activityRepository.findActivitiesForTest(testId, pageable);
    }

    /** Owner-checked live monitor snapshot. */
    @Transactional(readOnly = true)
    public MonitorSnapshot snapshotFor(Long testId, Long userId) {
        return snapshot(accessResolver.requireManageable(testId, userId));
    }

    /** Owner-checked submissions overview. */
    @Transactional(readOnly = true)
    public SubmissionsView submissionsFor(Long testId, Long userId, String query, int page) {
        return submissions(accessResolver.requireManageable(testId, userId), query, page);
    }

    /** Builds the live monitor snapshot (counts + per-enrolled-student state). */
    @Transactional(readOnly = true)
    public MonitorSnapshot snapshot(Test test) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<TestAttempt>> byUser = attemptsByUser(test.getId());

        int submitted = 0;
        int inProgress = 0;
        int active = 0;
        List<MonitorStudentRow> rows = new ArrayList<>();
        List<Enrollment> enrollments = enrollmentRepository
                .findAllByClassIdAndStatusOrderByJoinedAtDesc(
                        test.getClassId() == null ? -1L : test.getClassId(), Enrollment.STATUS_ACTIVE);
        for (Enrollment e : enrollments) {
            User u = e.getUser();
            List<TestAttempt> attempts = byUser.getOrDefault(u.getId(), List.of());
            String state = stateOf(attempts);
            LocalDateTime lastActivity = lastActivityOf(attempts);
            boolean isActive = "in-progress".equals(state)
                    && lastActivity != null
                    && Duration.between(lastActivity, now).getSeconds() <= ACTIVE_WINDOW_SECONDS;
            if ("submitted".equals(state)) submitted++;
            if ("in-progress".equals(state)) inProgress++;
            if (isActive) active++;
            rows.add(new MonitorStudentRow(u.getFullName(), u.getEmail(), state, lastActivity, isActive));
        }

        Long remaining = test.getEndAt() == null ? null
                : Math.max(0, Duration.between(now, test.getEndAt()).getSeconds());
        return new MonitorSnapshot(submitted, inProgress, active, test.getStatus(), remaining, rows);
    }

    /** Builds the paginated, searchable submissions overview. */
    @Transactional(readOnly = true)
    public SubmissionsView submissions(Test test, String query, int page) {
        List<TestAttempt> all = attemptRepository.findByTestId(test.getId());
        Map<Long, Integer> attemptCountByUser = new HashMap<>();
        List<TestAttempt> submittedAttempts = new ArrayList<>();
        for (TestAttempt a : all) {
            attemptCountByUser.merge(a.getUserId(), 1, Integer::sum);
            if (!a.isInProgress()) submittedAttempts.add(a);
        }
        Map<Long, User> users = loadUsers(submittedAttempts);

        boolean fixedWindow = !test.isIndividualTimer();
        String q = query == null ? "" : query.trim().toLowerCase();
        int lateCount = 0;
        List<SubmissionRow> rows = new ArrayList<>();
        for (TestAttempt a : submittedAttempts) {
            User u = users.get(a.getUserId());
            boolean late = fixedWindow && test.getEndAt() != null && a.getSubmittedAt() != null
                    && a.getSubmittedAt().isAfter(test.getEndAt());
            if (late) lateCount++;
            if (!matchesQuery(u, q)) continue;
            rows.add(new SubmissionRow(a.getId(),
                    u == null ? "?" : u.getFullName(), u == null ? "" : u.getEmail(),
                    a.getScore(), a.getCorrectCount(), a.getTotalQuestions(), a.getSubmittedAt(),
                    attemptCountByUser.getOrDefault(a.getUserId(), 1), late));
        }
        rows.sort((x, y) -> nz(y.submittedAt()).compareTo(nz(x.submittedAt())));

        Page<SubmissionRow> pageRows = paginate(rows, page);
        return new SubmissionsView(test.getId(), test.getTitle(), submittedAttempts.size(),
                lateCount, test.getStatus(), pageRows, query);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Map<Long, List<TestAttempt>> attemptsByUser(Long testId) {
        Map<Long, List<TestAttempt>> map = new HashMap<>();
        for (TestAttempt a : attemptRepository.findByTestId(testId)) {
            map.computeIfAbsent(a.getUserId(), k -> new ArrayList<>()).add(a);
        }
        return map;
    }

    private String stateOf(List<TestAttempt> attempts) {
        boolean submitted = false;
        boolean inProgress = false;
        for (TestAttempt a : attempts) {
            if (a.isInProgress()) inProgress = true;
            else submitted = true;
        }
        if (submitted) return "submitted";
        if (inProgress) return "in-progress";
        return "not-started";
    }

    private LocalDateTime lastActivityOf(List<TestAttempt> attempts) {
        LocalDateTime latest = null;
        for (TestAttempt a : attempts) {
            LocalDateTime ts = a.getLastActivityAt() != null ? a.getLastActivityAt() : a.getSubmittedAt();
            if (ts != null && (latest == null || ts.isAfter(latest))) latest = ts;
        }
        return latest;
    }

    private Map<Long, User> loadUsers(List<TestAttempt> attempts) {
        List<Long> ids = attempts.stream().map(TestAttempt::getUserId).distinct().toList();
        Map<Long, User> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        for (User u : userRepository.findAllById(ids)) map.put(u.getId(), u);
        return map;
    }

    private boolean matchesQuery(User u, String q) {
        if (q.isEmpty()) return true;
        if (u == null) return false;
        String name = u.getFullName() == null ? "" : u.getFullName().toLowerCase();
        String email = u.getEmail() == null ? "" : u.getEmail().toLowerCase();
        return name.contains(q) || email.contains(q);
    }

    private Page<SubmissionRow> paginate(List<SubmissionRow> rows, int page) {
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, DEFAULT_SUBMISSIONS_PAGE_SIZE);
        int from = Math.min(safePage * DEFAULT_SUBMISSIONS_PAGE_SIZE, rows.size());
        int to = Math.min(from + DEFAULT_SUBMISSIONS_PAGE_SIZE, rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    private static LocalDateTime nz(LocalDateTime v) {
        return v == null ? LocalDateTime.MIN : v;
    }
}
