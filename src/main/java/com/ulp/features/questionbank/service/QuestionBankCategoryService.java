package com.ulp.features.questionbank.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.dto.QuestionBankCategoryForm;
import com.ulp.features.questionbank.dto.QuestionBankViews.CategoryOption;
import com.ulp.features.questionbank.dto.QuestionBankViews.CategoryRow;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Department-scoped category CRUD for question bank curation. */
@Service
public class QuestionBankCategoryService {

    private static final String MSG_EMPTY_DEPARTMENT =
            "Bạn chưa được gán bộ môn để quản lý ngân hàng câu hỏi";
    private static final String MSG_FORBIDDEN =
            "Bạn không có quyền quản lý danh mục bộ môn này";
    private static final String MSG_NOT_FOUND = "Không tìm thấy danh mục";
    private static final String MSG_DUPLICATE = "Tên danh mục đã tồn tại trong bộ môn";
    private static final String MSG_IN_USE = "Không thể xoá: danh mục đang có câu hỏi";

    private final UserRepository userRepository;
    private final QuestionBankAccessPolicy accessPolicy;
    private final QuestionBankCategoryRepository categoryRepository;
    private final QuestionBankItemRepository itemRepository;

    public QuestionBankCategoryService(UserRepository userRepository,
                                       QuestionBankAccessPolicy accessPolicy,
                                       QuestionBankCategoryRepository categoryRepository,
                                       QuestionBankItemRepository itemRepository) {
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<CategoryOption> activeOptionsFor(User user) {
        Long departmentId = requireDepartment(user);
        return categoryRepository.findByDepartmentIdAndActiveTrueOrderByNameAsc(departmentId).stream()
                .map(category -> new CategoryOption(category.getId(), category.getName(), category.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryRow> rowsForCurator(Long userId) {
        User actor = requireCurator(userId);
        Long departmentId = requireDepartment(actor);
        return categoryRepository.findByDepartmentIdOrderByNameAsc(departmentId).stream()
                .map(category -> new CategoryRow(
                        category.getId(),
                        category.getName(),
                        category.getDescription(),
                        category.isActive(),
                        itemRepository.countByCategoryId(category.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public QuestionBankCategoryForm loadForm(Long userId, Long categoryId) {
        User actor = requireCurator(userId);
        QuestionBankCategory category = requireSameDepartmentCategory(categoryId, actor);
        QuestionBankCategoryForm form = new QuestionBankCategoryForm();
        form.setName(category.getName());
        form.setDescription(category.getDescription());
        form.setActive(category.isActive());
        return form;
    }

    @Transactional
    public void create(Long userId, QuestionBankCategoryForm form) {
        User actor = requireCurator(userId);
        Long departmentId = requireDepartment(actor);
        String name = normalizeName(form.getName());
        if (categoryRepository.existsByDepartmentIdAndNameIgnoreCase(departmentId, name)) {
            throw new QuestionBankValidationException(MSG_DUPLICATE);
        }
        categoryRepository.save(new QuestionBankCategory(
                departmentId,
                name,
                blankToNull(form.getDescription()),
                form.isActive(),
                actor.getId()));
    }

    @Transactional
    public void update(Long userId, Long categoryId, QuestionBankCategoryForm form) {
        User actor = requireCurator(userId);
        QuestionBankCategory category = requireSameDepartmentCategory(categoryId, actor);
        String name = normalizeName(form.getName());
        if (categoryRepository.existsByDepartmentIdAndNameIgnoreCaseAndIdNot(
                category.getDepartmentId(), name, category.getId())) {
            throw new QuestionBankValidationException(MSG_DUPLICATE);
        }
        category.updateDetails(name, blankToNull(form.getDescription()), form.isActive());
        categoryRepository.save(category);
    }

    @Transactional
    public void toggle(Long userId, Long categoryId) {
        User actor = requireCurator(userId);
        QuestionBankCategory category = requireSameDepartmentCategory(categoryId, actor);
        category.updateDetails(category.getName(), category.getDescription(), !category.isActive());
        categoryRepository.save(category);
    }

    @Transactional
    public void delete(Long userId, Long categoryId) {
        User actor = requireCurator(userId);
        QuestionBankCategory category = requireSameDepartmentCategory(categoryId, actor);
        if (itemRepository.countByCategoryId(category.getId()) > 0) {
            throw new QuestionBankValidationException(MSG_IN_USE);
        }
        categoryRepository.delete(category);
    }

    @Transactional(readOnly = true)
    public QuestionBankCategory requireVisibleCategory(Long categoryId, User actor) {
        Long departmentId = requireDepartment(actor);
        if (!accessPolicy.canAccessDepartment(actor, departmentId)) {
            throw new QuestionBankValidationException(MSG_FORBIDDEN);
        }
        return categoryRepository.findByIdAndDepartmentId(categoryId, departmentId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_NOT_FOUND));
    }

    private User requireCurator(Long userId) {
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_FORBIDDEN));
        if (actor.getRole() != Role.HEAD && actor.getRole() != Role.ADMIN) {
            throw new QuestionBankValidationException(MSG_FORBIDDEN);
        }
        requireDepartment(actor);
        return actor;
    }

    private QuestionBankCategory requireSameDepartmentCategory(Long categoryId, User actor) {
        Long departmentId = requireDepartment(actor);
        if (!accessPolicy.canCurateDepartment(actor, departmentId)) {
            throw new QuestionBankValidationException(MSG_FORBIDDEN);
        }
        return categoryRepository.findByIdAndDepartmentId(categoryId, departmentId)
                .orElseThrow(() -> new QuestionBankValidationException(MSG_NOT_FOUND));
    }

    private Long requireDepartment(User actor) {
        Long departmentId = accessPolicy.resolveDepartmentId(actor);
        if (departmentId == null) {
            throw new QuestionBankValidationException(MSG_EMPTY_DEPARTMENT);
        }
        return departmentId;
    }

    private static String normalizeName(String value) {
        String name = blankToNull(value);
        if (name == null) {
            throw new QuestionBankValidationException("Tên danh mục không được để trống");
        }
        return name;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
