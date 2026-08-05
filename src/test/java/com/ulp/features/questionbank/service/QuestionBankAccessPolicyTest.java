package com.ulp.features.questionbank.service;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for question bank access across the two ownership scopes: the HEAD
 * bank ({@code ownerId null}) and lecturer-private banks ({@code ownerId}).
 */
class QuestionBankAccessPolicyTest {

    private final HeadDepartmentResolver headDepartmentResolver = mock(HeadDepartmentResolver.class);
    private final QuestionBankAccessPolicy policy = new QuestionBankAccessPolicy(headDepartmentResolver);

    @Test
    void lecturer_can_read_own_department_head_bank_but_cannot_manage() {
        User lecturer = user(Role.LECTURER, 10L, 5L);

        assertThat(policy.resolveDepartmentId(lecturer)).isEqualTo(5L);
        assertThat(policy.canReadHeadBank(lecturer, 5L)).isTrue();
        assertThat(policy.canReadHeadBank(lecturer, 7L)).isFalse();
        assertThat(policy.canManageHeadBank(lecturer, 5L)).isFalse();
    }

    @Test
    void head_uses_resolved_department_for_read_and_manage() {
        User head = user(Role.HEAD, 11L, 99L);
        Department department = department(5L, head.getId());
        when(headDepartmentResolver.resolve(head.getId())).thenReturn(Optional.of(department));

        assertThat(policy.resolveDepartmentId(head)).isEqualTo(5L);
        assertThat(policy.canReadHeadBank(head, 5L)).isTrue();
        assertThat(policy.canManageHeadBank(head, 5L)).isTrue();
        assertThat(policy.canReadHeadBank(head, 6L)).isFalse();
    }

    @Test
    void admin_is_scoped_by_department_assignment() {
        User admin = user(Role.ADMIN, 12L, 8L);

        assertThat(policy.canReadHeadBank(admin, 8L)).isTrue();
        assertThat(policy.canManageHeadBank(admin, 8L)).isTrue();
        assertThat(policy.canReadHeadBank(admin, 9L)).isFalse();
    }

    @Test
    void private_bank_item_is_owner_only_for_read_and_manage() {
        User owner = user(Role.LECTURER, 20L, 5L);
        User other = user(Role.LECTURER, 21L, 5L);
        QuestionBankItem item = headOrPrivateItem(5L, owner.getId(), 50L);

        assertThat(policy.canAccessLecturerBank(item, owner)).isTrue();
        assertThat(policy.canAccessLecturerBank(item, other)).isFalse();
        assertThat(policy.canReadItem(item, owner)).isTrue();
        assertThat(policy.canReadItem(item, other)).isFalse();
        assertThat(policy.canManageItem(item, other)).isFalse();
    }

    @Test
    void head_bank_item_is_readable_by_department_but_managed_only_by_curator() {
        User head = user(Role.HEAD, 11L, 99L);
        Department department = department(5L, head.getId());
        when(headDepartmentResolver.resolve(head.getId())).thenReturn(Optional.of(department));
        User lecturer = user(Role.LECTURER, 10L, 5L);
        QuestionBankItem item = headOrPrivateItem(5L, null, 51L);

        assertThat(policy.canReadItem(item, head)).isTrue();
        assertThat(policy.canManageItem(item, head)).isTrue();
        assertThat(policy.canReadItem(item, lecturer)).isTrue();
        assertThat(policy.canManageItem(item, lecturer)).isFalse();
    }

    private static QuestionBankItem headOrPrivateItem(Long departmentId, Long ownerId, Long id) {
        QuestionBankItem item = new QuestionBankItem(
                departmentId, 1L, ownerId, null, 10L,
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_ACTIVE, "<p>Q</p>", null);
        try {
            Field idField = QuestionBankItem.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return item;
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
