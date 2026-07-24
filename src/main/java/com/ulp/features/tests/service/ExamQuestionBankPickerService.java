package com.ulp.features.tests.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.entity.QuestionBankOption;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.questionbank.service.QuestionBankAccessPolicy;
import com.ulp.features.tests.dto.LecturerTestDtos.BankCategoryOption;
import com.ulp.features.tests.dto.LecturerTestDtos.BankItemSnapshot;
import com.ulp.features.tests.dto.LecturerTestDtos.BankOptionSnapshot;
import com.ulp.features.tests.entity.Test;
import com.ulp.features.tests.repository.TestRepository;
import com.ulp.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Queries approved shared questions from the department that owns a given test.
 * The bank is department-scoped (not test-scoped); the {@code testId} only
 * resolves and authorizes the working department. Results are returned as
 * immutable snapshots so the exam builder can copy the current approved
 * contribution into exam-owned rows without live-link semantics.
 */
@Service
public class ExamQuestionBankPickerService {

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final TestRepository testRepository;
    private final ClassRepository classRepository;
    private final QuestionBankCategoryRepository categoryRepository;
    private final QuestionBankItemRepository itemRepository;
    private final QuestionBankOptionRepository optionRepository;

    public ExamQuestionBankPickerService(UserRepository userRepository,
                                         QuestionBankAccessPolicy accessPolicy,
                                         TestRepository testRepository,
                                         ClassRepository classRepository,
                                         QuestionBankCategoryRepository categoryRepository,
                                         QuestionBankItemRepository itemRepository,
                                         QuestionBankOptionRepository optionRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.testRepository = testRepository;
        this.classRepository = classRepository;
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.optionRepository = optionRepository;
    }

    /** Active categories the current actor may search within for this test. */
    @Transactional(readOnly = true)
    public List<BankCategoryOption> categoriesFor(Long userId, Role role, Long testId) {
        User actor = requireActor(userId, role);
        Long departmentId = resolveAccessibleTestDepartment(actor, testId);
        if (departmentId == null) {
            return List.of();
        }
        return categoryRepository.findByDepartmentIdAndActiveTrueOrderByNameAsc(departmentId).stream()
                .map(category -> new BankCategoryOption(category.getId(), category.getName()))
                .toList();
    }

    /** Approved shared items visible to the actor's department (resolved via the test). */
    @Transactional(readOnly = true)
    public List<BankItemSnapshot> searchApproved(Long userId, Role role, Long testId, Long categoryId, String query) {
        Long departmentId = requireTestDepartment(requireActor(userId, role), testId);
        String normalizedQuery = normalizeQuery(query);
        Map<Long, QuestionBankCategory> categories = categoriesById(departmentId);
        List<QuestionBankItem> items = itemRepository
                .findByDepartmentIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                        departmentId, List.of(QuestionBankItem.STATUS_APPROVED))
                .stream()
                .filter(item -> categoryId == null || categoryId.equals(item.getCategoryId()))
                .filter(item -> matchesQuery(item, categories.get(item.getCategoryId()), normalizedQuery))
                .limit(20)
                .toList();
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QuestionBankOption>> optionsByItem = loadOptions(items);
        return items.stream()
                .map(item -> toSnapshot(item, categories.get(item.getCategoryId()), optionsByItem))
                .toList();
    }

    /**
     * Loads approved snapshots for the given bank item ids, department-scoped via
     * the test. Only approved items in the actor's department are returned; any id
     * outside that set is silently dropped so the caller inserts a safe subset.
     */
    @Transactional(readOnly = true)
    public List<BankItemSnapshot> approvedSnapshotsByIds(Long userId, Role role, Long testId, List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        Long departmentId = requireTestDepartment(requireActor(userId, role), testId);
        Map<Long, QuestionBankCategory> categories = categoriesById(departmentId);
        Set<Long> wanted = new LinkedHashSet<>(itemIds);
        List<QuestionBankItem> items = itemRepository
                .findByDepartmentIdAndWorkflowStatusInOrderByUpdatedAtDescIdDesc(
                        departmentId, List.of(QuestionBankItem.STATUS_APPROVED))
                .stream()
                .filter(item -> wanted.contains(item.getId()))
                .toList();
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QuestionBankOption>> optionsByItem = loadOptions(items);
        // Preserve the caller's requested order so inserted questions land predictably.
        Map<Long, BankItemSnapshot> byId = new LinkedHashMap<>();
        for (QuestionBankItem item : items) {
            byId.put(item.getId(), toSnapshot(item, categories.get(item.getCategoryId()), optionsByItem));
        }
        List<BankItemSnapshot> ordered = new ArrayList<>();
        for (Long id : itemIds) {
            BankItemSnapshot snapshot = byId.get(id);
            if (snapshot != null) {
                ordered.add(snapshot);
            }
        }
        return ordered;
    }

    private BankItemSnapshot toSnapshot(QuestionBankItem item,
                                        QuestionBankCategory category,
                                        Map<Long, List<QuestionBankOption>> optionsByItem) {
        List<BankOptionSnapshot> options = optionsByItem.getOrDefault(item.getId(), List.of()).stream()
                .map(option -> new BankOptionSnapshot(option.getContent(), option.isCorrect()))
                .toList();
        return new BankItemSnapshot(item.getId(), category == null ? "—" : category.getName(),
                item.getQuestionType(), item.getContent(), item.getExplanation(), options);
    }

    private Map<Long, List<QuestionBankOption>> loadOptions(List<QuestionBankItem> items) {
        Map<Long, List<QuestionBankOption>> map = new LinkedHashMap<>();
        List<Long> ids = items.stream().map(QuestionBankItem::getId).toList();
        for (QuestionBankOption option : optionRepository.findByItemIdInOrderBySortOrderAscIdAsc(ids)) {
            map.computeIfAbsent(option.getItemId(), ignored -> new ArrayList<>()).add(option);
        }
        return map;
    }

    private Map<Long, QuestionBankCategory> categoriesById(Long departmentId) {
        Map<Long, QuestionBankCategory> map = new LinkedHashMap<>();
        for (QuestionBankCategory category : categoryRepository.findByDepartmentIdOrderByNameAsc(departmentId)) {
            map.put(category.getId(), category);
        }
        return map;
    }

    private boolean matchesQuery(QuestionBankItem item, QuestionBankCategory category, String query) {
        if (query == null) {
            return true;
        }
        String categoryName = category == null ? "" : category.getName().toLowerCase();
        return preview(item.getContent()).toLowerCase().contains(query) || categoryName.contains(query);
    }

    private User requireActor(Long userId, Role role) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác"));
        if (actor.getRole() != role) {
            throw new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác");
        }
        return actor;
    }

    private Long requireTestDepartment(User actor, Long testId) {
        Long departmentId = resolveAccessibleTestDepartment(actor, testId);
        if (departmentId == null) {
            throw new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác");
        }
        return departmentId;
    }

    private Long resolveAccessibleTestDepartment(User actor, Long testId) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác"));
        ClassEntity clazz = classRepository.findById(test.getClassId())
                .orElseThrow(() -> new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác"));
        Long departmentId = clazz.getDepartmentId();
        if (departmentId == null) {
            return null;
        }
        if (!accessPolicy.canAccessDepartment(actor, departmentId)) {
            throw new AccessDeniedException("Bạn không có quyền dùng câu hỏi cộng tác");
        }
        return departmentId;
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim().toLowerCase();
    }

    private static String preview(String html) {
        return html == null ? "" : html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
