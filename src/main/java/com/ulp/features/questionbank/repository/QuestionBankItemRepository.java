package com.ulp.features.questionbank.repository;

import com.ulp.features.questionbank.entity.QuestionBankItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for subject → chapter organised question-bank items across both
 * ownership scopes: the HEAD bank ({@code ownerId IS NULL}) and per-lecturer
 * private banks ({@code ownerId = user.id}).
 */
public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, Long> {

    long countByOwnerIdAndSubjectId(Long ownerId, Long subjectId);

    long countByOwnerIdIsNullAndDepartmentIdAndSubjectId(Long departmentId, Long subjectId);

    // ── Lecturer-private bank (ownerId = user.id) ────────────────────

    List<QuestionBankItem> findByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId);

    List<QuestionBankItem> findByOwnerIdAndSubjectIdOrderByUpdatedAtDescIdDesc(Long ownerId, Long subjectId);

    // ── HEAD bank (ownerId IS NULL, department-owned) ────────────────

    List<QuestionBankItem> findByOwnerIdIsNullAndDepartmentIdOrderByUpdatedAtDescIdDesc(Long departmentId);

    List<QuestionBankItem> findByOwnerIdIsNullAndDepartmentIdAndSubjectIdOrderByUpdatedAtDescIdDesc(
            Long departmentId, Long subjectId);
}
