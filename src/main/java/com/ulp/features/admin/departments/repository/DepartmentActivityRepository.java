package com.ulp.features.admin.departments.repository;

import com.ulp.entities.DepartmentActivity;
import com.ulp.entities.User;
import com.ulp.features.admin.departments.dto.DepartmentActivityRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Append-only repository for {@link DepartmentActivity}.
 */
public interface DepartmentActivityRepository extends JpaRepository<DepartmentActivity, Long> {

    /**
     * Paged audit history for one department with actor email via LEFT JOIN.
     *
     * @param departmentId target department
     * @param pageable     paging (sort ignored — query orders by createdAt DESC)
     */
    @Query("SELECT new com.ulp.features.admin.departments.dto.DepartmentActivityRow(" +
           "  a.id, a.type, a.message, u.email, a.createdAt) " +
           "FROM DepartmentActivity a " +
           "LEFT JOIN User u ON u.id = a.performedBy " +
           "WHERE a.departmentId = :departmentId " +
           "ORDER BY a.createdAt DESC, a.id DESC")
    Page<DepartmentActivityRow> findActivitiesForDepartment(
            @Param("departmentId") Long departmentId,
            Pageable pageable);
}
