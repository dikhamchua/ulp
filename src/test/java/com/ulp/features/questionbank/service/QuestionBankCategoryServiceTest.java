package com.ulp.features.questionbank.service;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.dto.QuestionBankCategoryForm;
import com.ulp.features.questionbank.entity.QuestionBankCategory;
import com.ulp.features.questionbank.repository.QuestionBankCategoryRepository;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for department-scoped category validation. */
class QuestionBankCategoryServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final QuestionBankCategoryRepository categoryRepository = mock(QuestionBankCategoryRepository.class);
    private final QuestionBankItemRepository itemRepository = mock(QuestionBankItemRepository.class);
    private final HeadDepartmentResolver resolver = mock(HeadDepartmentResolver.class);
    private final QuestionBankAccessPolicy accessPolicy = new QuestionBankAccessPolicy(resolver);
    private final QuestionBankCategoryService service = new QuestionBankCategoryService(
            userRepository, accessPolicy, categoryRepository, itemRepository);

    @Test
    void rejects_duplicate_name_in_same_department() {
        User head = user(Role.HEAD, 10L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankCategoryForm form = new QuestionBankCategoryForm();
        form.setName("Chương 1");

        when(userRepository.findById(head.getId())).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(categoryRepository.existsByDepartmentIdAndNameIgnoreCase(5L, "Chương 1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(head.getId(), form))
                .isInstanceOf(QuestionBankValidationException.class)
                .hasMessageContaining("đã tồn tại");
    }

    @Test
    void deletes_only_when_category_has_no_items() {
        User head = user(Role.HEAD, 10L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankCategory category = new QuestionBankCategory(5L, "Grammar", null, true, head.getId());
        setId(category, 3L);

        when(userRepository.findById(head.getId())).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(categoryRepository.findByIdAndDepartmentId(3L, 5L)).thenReturn(Optional.of(category));
        when(itemRepository.countByCategoryId(3L)).thenReturn(0L);

        service.delete(head.getId(), 3L);

        verify(categoryRepository).delete(category);
    }

    private static User user(Role role, Long id, Long departmentId) {
        try {
            Constructor<User> ctor = User.class.getDeclaredConstructor(
                    String.class, String.class, String.class, Role.class,
                    boolean.class, boolean.class, boolean.class, boolean.class,
                    String.class, String.class);
            ctor.setAccessible(true);
            User user = ctor.newInstance("u@example.com", "hash", "User", role,
                    true, true, false, false, null, null);
            user.setDepartmentId(departmentId);
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
            return user;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Department department(Long id, Long headUserId) {
        try {
            Department department = new Department("CNTT", "CNTT", null, true);
            department.assignHead(headUserId);
            Field idField = Department.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(department, id);
            return department;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void setId(QuestionBankCategory category, Long id) {
        try {
            Field idField = QuestionBankCategory.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(category, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
