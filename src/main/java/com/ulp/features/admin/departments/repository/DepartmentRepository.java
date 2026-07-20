package com.ulp.features.admin.departments.repository;

import com.ulp.entities.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Department}.
 */
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByOrderByNameAsc();

    List<Department> findByActiveTrueOrderByNameAsc();

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    Optional<Department> findFirstByHeadUserId(Long headUserId);

    boolean existsByHeadUserId(Long headUserId);

    long countByHeadUserId(Long headUserId);
}
