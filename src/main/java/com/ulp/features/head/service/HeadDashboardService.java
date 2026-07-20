package com.ulp.features.head.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.head.dto.HeadDtos.DashboardKpis;
import com.ulp.features.head.dto.HeadDtos.DashboardView;
import com.ulp.features.head.dto.HeadDtos.DepartmentSummary;
import com.ulp.features.head.dto.HeadDtos.RecentClassRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates department-scoped KPIs and recent classes for the HEAD dashboard.
 */
@Service
public class HeadDashboardService {

    private static final int RECENT_LIMIT = 5;

    private final HeadDepartmentResolver resolver;
    private final ClassRepository classRepository;
    private final JdbcTemplate jdbc;

    public HeadDashboardService(HeadDepartmentResolver resolver,
                                ClassRepository classRepository,
                                JdbcTemplate jdbc) {
        this.resolver = resolver;
        this.classRepository = classRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardView load(Long headUserId) {
        Optional<Department> deptOpt = resolver.resolve(headUserId);
        if (deptOpt.isEmpty()) {
            return new DashboardView(null, new DashboardKpis(0, 0, 0, 0), List.of(), true);
        }
        Department dept = deptOpt.get();
        Long deptId = dept.getId();

        long classCount = classRepository.countByDepartmentId(deptId);
        long lecturerCount = countOrZero(
                "SELECT COUNT(*) FROM users WHERE is_deleted = 0 AND is_active = 1 "
                        + "AND department_id = ? AND role IN ('LECTURER','HEAD')",
                deptId);
        long studentCount = countOrZero(
                "SELECT COUNT(DISTINCT e.user_id) FROM enrollments e "
                        + "INNER JOIN classes c ON c.id = e.class_id "
                        + "WHERE e.status = 'ACTIVE' AND c.is_deleted = 0 AND c.department_id = ?",
                deptId);
        long courseCount = countOrZero(
                "SELECT COUNT(*) FROM courses WHERE is_deleted = 0 AND department_id = ?",
                deptId);

        List<ClassEntity> recent = classRepository
                .findAllByDepartmentId(deptId,
                        PageRequest.of(0, RECENT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        Map<Long, String> lecturerNames = loadLecturerNames(recent);
        List<RecentClassRow> rows = new ArrayList<>(recent.size());
        for (ClassEntity c : recent) {
            rows.add(new RecentClassRow(
                    c.getId(), c.getName(), c.getCode(), c.getStatus(),
                    lecturerNames.getOrDefault(c.getLecturerId(), "—"),
                    c.getCreatedAt()));
        }

        return new DashboardView(
                new DepartmentSummary(dept.getId(), dept.getCode(), dept.getName()),
                new DashboardKpis(classCount, lecturerCount, studentCount, courseCount),
                rows,
                false);
    }

    private Map<Long, String> loadLecturerNames(List<ClassEntity> classes) {
        Map<Long, String> names = new HashMap<>();
        for (ClassEntity c : classes) {
            if (c.getLecturerId() != null && !names.containsKey(c.getLecturerId())) {
                List<String> found = jdbc.query(
                        "SELECT full_name FROM users WHERE id = ?",
                        (rs, i) -> rs.getString(1),
                        c.getLecturerId());
                if (!found.isEmpty()) {
                    names.put(c.getLecturerId(), found.get(0));
                }
            }
        }
        return names;
    }

    private long countOrZero(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }
}
