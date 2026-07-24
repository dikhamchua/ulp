package com.ulp.features.questionbank.service;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for department-scoped question bank access.
 */
class QuestionBankAccessPolicyTest {

    private final HeadDepartmentResolver headDepartmentResolver = mock(HeadDepartmentResolver.class);
    private final QuestionBankAccessPolicy policy = new QuestionBankAccessPolicy(headDepartmentResolver);

    @Test
    void lecturer_can_access_own_department_but_cannot_curate() {
        User lecturer = user(Role.LECTURER, 10L, 5L);

        assertThat(policy.resolveDepartmentId(lecturer)).isEqualTo(5L);
        assertThat(policy.canAccessDepartment(lecturer, 5L)).isTrue();
        assertThat(policy.canAccessDepartment(lecturer, 7L)).isFalse();
        assertThat(policy.canCurateDepartment(lecturer, 5L)).isFalse();
    }

    @Test
    void head_uses_resolved_working_department_for_access_and_curation() {
        User head = user(Role.HEAD, 11L, 99L);
        Department department = department(5L, head.getId());
        when(headDepartmentResolver.resolve(head.getId())).thenReturn(Optional.of(department));

        assertThat(policy.resolveDepartmentId(head)).isEqualTo(5L);
        assertThat(policy.canAccessDepartment(head, 5L)).isTrue();
        assertThat(policy.canCurateDepartment(head, 5L)).isTrue();
        assertThat(policy.canAccessDepartment(head, 6L)).isFalse();
    }

    @Test
    void admin_is_scoped_by_department_assignment() {
        User admin = user(Role.ADMIN, 12L, 8L);

        assertThat(policy.canAccessDepartment(admin, 8L)).isTrue();
        assertThat(policy.canCurateDepartment(admin, 8L)).isTrue();
        assertThat(policy.canAccessDepartment(admin, 9L)).isFalse();
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
}
