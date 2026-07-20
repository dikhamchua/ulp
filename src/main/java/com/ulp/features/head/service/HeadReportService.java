package com.ulp.features.head.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.dto.HeadDtos.ReportClassRow;
import com.ulp.features.head.dto.HeadDtos.ReportView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the department class comparison report for HEAD.
 */
@Service
public class HeadReportService {

    private final HeadDepartmentResolver resolver;
    private final ClassRepository classRepository;
    private final JdbcTemplate jdbc;

    public HeadReportService(HeadDepartmentResolver resolver,
                             ClassRepository classRepository,
                             JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ReportView load(Long headUserId) {
        Optional<Department> deptOpt = resolver.resolve(headUserId);
        if (deptOpt.isEmpty()) {
            return new ReportView(null, List.of(), true);
        }
        Department dept = deptOpt.get();
        List<ClassEntity> classes =
                classRepository.findAllByDepartmentIdOrderByCreatedAtDesc(dept.getId());
        List<ReportClassRow> rows = new ArrayList<>(classes.size());
        for (ClassEntity c : classes) {
            long enrollments = countActiveEnrollments(c.getId());
            BigDecimal avgTest = avgTestScore(c.getId());
            BigDecimal avgAsg = avgAssignmentScore(c.getId());
            rows.add(new ReportClassRow(
                    c.getId(), c.getName(), c.getCode(),
                    enrollments, avgTest, avgAsg));
        }
        return new ReportView(
                new DepartmentSummary(dept.getId(), dept.getCode(), dept.getName()),
                rows, false);
    }

    private long countActiveEnrollments(Long classId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM enrollments WHERE class_id = ? AND status = 'ACTIVE'",
                Long.class, classId);
        return n == null ? 0L : n;
    }

    private BigDecimal avgTestScore(Long classId) {
        return jdbc.query(
                "SELECT AVG(ta.score) AS avg_score FROM test_attempts ta "
                        + "INNER JOIN tests t ON t.id = ta.test_id "
                        + "WHERE t.class_id = ? AND t.is_deleted = 0 "
                        + "AND ta.status IN ('SUBMITTED','TIMED_OUT') AND ta.score IS NOT NULL",
                rs -> rs.next() ? rs.getBigDecimal("avg_score") : null,
                classId);
    }

    private BigDecimal avgAssignmentScore(Long classId) {
        return jdbc.query(
                "SELECT AVG(af.score) AS avg_score FROM assignment_feedback af "
                        + "INNER JOIN assignment_submissions s ON s.id = af.submission_id "
                        + "INNER JOIN assignments a ON a.id = s.assignment_id "
                        + "WHERE a.class_id = ? AND a.is_deleted = 0",
                rs -> rs.next() ? rs.getBigDecimal("avg_score") : null,
                classId);
    }
}
