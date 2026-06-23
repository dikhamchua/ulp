package com.ulp.admin.service;

import com.ulp.admin.dto.AdminDashboardDtos.DashboardStats;
import com.ulp.admin.dto.AdminDashboardDtos.RecentClass;
import com.ulp.admin.dto.AdminDashboardDtos.UserRoleCount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Provides aggregated statistics for the Admin Dashboard page.
 *
 * <p>Uses {@link JdbcTemplate} instead of JPA repository entities because:
 * <ul>
 *   <li>Only simple {@code COUNT} / {@code GROUP BY} queries are needed — full
 *       Department and Course entities do not yet exist.</li>
 *   <li>Avoids loading entire rows into memory just to obtain a single number.</li>
 * </ul>
 * Once Course/Department CRUD is implemented in a later sprint, these queries
 * should be migrated to the corresponding repositories.
 */
@Service
public class AdminDashboardService {

    private final JdbcTemplate jdbc;

    /**
     * Creates an {@code AdminDashboardService} with the given {@link JdbcTemplate}.
     *
     * @param jdbc the JDBC template used to execute aggregate queries
     */
    public AdminDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns high-level platform statistics for the dashboard summary cards.
     *
     * <p>Counts active (non-deleted, active) users, non-deleted classes,
     * active departments, and non-deleted courses in a single read-only
     * transaction.
     *
     * @return a {@link DashboardStats} record containing the four counts
     */
    @Transactional(readOnly = true)
    public DashboardStats stats() {
        Long userCount = countOrZero("SELECT COUNT(*) FROM users WHERE is_deleted = 0 AND is_active = 1");
        Long classCount = countOrZero("SELECT COUNT(*) FROM classes WHERE is_deleted = 0");
        Long departmentCount = countOrZero("SELECT COUNT(*) FROM departments WHERE is_active = 1");
        Long courseCount = countOrZero("SELECT COUNT(*) FROM courses WHERE is_deleted = 0");
        return new DashboardStats(userCount, classCount, departmentCount, courseCount);
    }

    /**
     * Returns the number of active users grouped by role, ordered alphabetically.
     *
     * <p>Only users that are neither soft-deleted nor deactivated are included.
     *
     * @return a list of {@link UserRoleCount} records, one per distinct role
     */
    @Transactional(readOnly = true)
    public List<UserRoleCount> usersByRole() {
        return jdbc.query(
                "SELECT role, COUNT(*) AS cnt FROM users " +
                        "WHERE is_deleted = 0 AND is_active = 1 " +
                        "GROUP BY role ORDER BY role",
                (rs, i) -> new UserRoleCount(rs.getString("role"), rs.getLong("cnt"))
        );
    }

    /**
     * Returns the most recently created classes for the dashboard activity feed.
     *
     * <p>Each result includes the class id, name, code, status, creation timestamp,
     * and the full name of the assigned lecturer (via a {@code LEFT JOIN} so classes
     * without a lecturer are still included).
     *
     * @param limit maximum number of classes to return
     * @return a list of {@link RecentClass} records ordered by creation date descending
     */
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
