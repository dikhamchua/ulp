package com.ulp.features.questionbank.repository;

import com.ulp.features.questionbank.entity.QuestionBankCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for department-scoped question bank categories.
 */
public interface QuestionBankCategoryRepository extends JpaRepository<QuestionBankCategory, Long> {

    List<QuestionBankCategory> findByDepartmentIdOrderByNameAsc(Long departmentId);

    List<QuestionBankCategory> findByDepartmentIdAndActiveTrueOrderByNameAsc(Long departmentId);

    Optional<QuestionBankCategory> findByIdAndDepartmentId(Long id, Long departmentId);

    boolean existsByDepartmentIdAndNameIgnoreCase(Long departmentId, String name);

    boolean existsByDepartmentIdAndNameIgnoreCaseAndIdNot(Long departmentId, String name, Long id);
}
