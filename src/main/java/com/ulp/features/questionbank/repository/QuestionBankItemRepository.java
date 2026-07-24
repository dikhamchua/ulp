package com.ulp.features.questionbank.repository;

import com.ulp.features.questionbank.entity.QuestionBankItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for department-scoped shared question contributions.
 */
public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, Long> {

    long countByCategoryId(Long categoryId);

    List<QuestionBankItem> findByDepartmentIdOrderByUpdatedAtDescIdDesc(Long departmentId);

    List<QuestionBankItem> findByDepartmentIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
            Long departmentId, Collection<String> workflowStatuses);

    Optional<QuestionBankItem> findByIdAndDepartmentId(Long id, Long departmentId);
}
