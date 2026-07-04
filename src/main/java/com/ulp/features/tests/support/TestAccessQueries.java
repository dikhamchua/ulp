package com.ulp.features.tests.support;

import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared read helpers that compute which exams a student may access, reused by
 * the catalog, practice and readiness services so the "accessible pool"
 * definition lives in exactly one place.
 */
@Component
public class TestAccessQueries {

    private final EnrollmentRepository enrollmentRepository;
    private final TestRepository testRepository;

    public TestAccessQueries(EnrollmentRepository enrollmentRepository,
                             TestRepository testRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.testRepository = testRepository;
    }

    /** Class ids the student is ACTIVE-enrolled in. */
    public List<Long> activeClassIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        for (Enrollment e : enrollmentRepository
                .findAllByUserIdAndStatusOrderByJoinedAtDesc(userId, Enrollment.STATUS_ACTIVE)) {
            ids.add(e.getClassId());
        }
        return ids;
    }

    /**
     * Every exam the student may take: PUBLISHED exams in their ACTIVE-enrolled
     * classes plus their own PRACTICE tests. De-duplicated by id, newest-updated
     * first (own-practice appended after class exams).
     */
    public List<Test> accessibleExams(Long userId) {
        Map<Long, Test> byId = new LinkedHashMap<>();
        List<Long> classIds = activeClassIds(userId);
        if (!classIds.isEmpty()) {
            for (Test t : testRepository.findByClassIdInAndStatusOrderByUpdatedAtDesc(
                    classIds, Test.STATUS_PUBLISHED)) {
                byId.put(t.getId(), t);
            }
        }
        for (Test t : testRepository.findByCreatedByAndTypeOrderByUpdatedAtDesc(
                userId, Test.TYPE_PRACTICE)) {
            byId.putIfAbsent(t.getId(), t);
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * The accessible PUBLISHED MOCK/MODULE exams used by the readiness score
     * (practice tests are excluded — readiness measures graded exams).
     */
    public List<Test> accessibleGradedExams(Long userId) {
        List<Long> classIds = activeClassIds(userId);
        if (classIds.isEmpty()) return List.of();
        return testRepository.findByClassIdInAndStatusAndTypeInOrderByUpdatedAtDesc(
                classIds, Test.STATUS_PUBLISHED, List.of(Test.TYPE_MOCK, Test.TYPE_MODULE));
    }
}
