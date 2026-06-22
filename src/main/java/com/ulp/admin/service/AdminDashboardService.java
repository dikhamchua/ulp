package com.ulp.admin.service;

import com.ulp.admin.dto.AdminDashboardDtos.DashboardStats;
import com.ulp.admin.dto.AdminDashboardDtos.RecentClass;
import com.ulp.admin.dto.AdminDashboardDtos.UserRoleCount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cung cap so lieu cho trang Admin Dashboard.
 *
 * <p>Su dung {@link JdbcTemplate} thay vi repository entities vi:
 * <ul>
 *   <li>Chi can COUNT / GROUP BY — entity Department/Course chua co.</li>
 *   <li>Tranh load full row vao memory cho 1 con so.</li>
 * </ul>
 * Sprint sau khi co Course/Department CRUD se chuyen sang repository.
 */
@Service
public class AdminDashboardService {

    private final JdbcTemplate jdbc;

    public AdminDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardStats stats() {
        Long userCount = countOrZero("SELECT COUNT(*) FROM users WHERE is_deleted = 0 AND is_active = 1");
        Long classCount = countOrZero("SELECT COUNT(*) FROM classes WHERE is_deleted = 0");
        Long departmentCount = countOrZero("SELECT COUNT(*) FROM departments WHERE is_active = 1");
        Long courseCount = countOrZero("SELECT COUNT(*) FROM courses WHERE is_deleted = 0");
        return new DashboardStats(userCount, classCount, departmentCount, courseCount);
    }

    @Transactional(readOnly = true)
    public List<UserRoleCount> usersByRole() {
        return jdbc.query(
                "SELECT role, COUNT(*) AS cnt FROM users " +
                        "WHERE is_deleted = 0 AND is_active = 1 " +
                        "GROUP BY role ORDER BY role",
                (rs, i) -> new UserRoleCount(rs.getString("role"), rs.getLong("cnt"))
        );
    }

    @Transactional(readOnly = true)
    public List<RecentClass> recentClasses(int limit) {
        return jdbc.query(
                "SELECT c.id, c.name, c.code, c.status, c.created_at, u.full_name AS lecturer_name " +
                        "FROM classes c " +
                        "LEFT JOIN users u ON u.id = c.lecturer_id " +
                        "WHERE c.is_deleted = 0 " +
                        "ORDER BY c.created_at DESC LIMIT ?",
                (rs, i) -> new RecentClass(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("code"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getString("lecturer_name")
                ),
                limit
        );
    }

    private Long countOrZero(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n != null ? n : 0L;
    }
}
