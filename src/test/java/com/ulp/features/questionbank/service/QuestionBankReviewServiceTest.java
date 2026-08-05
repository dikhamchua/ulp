package com.ulp.features.questionbank.service;

import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.Test;

import com.ulp.features.questionbank.service.QuestionBankReviewService.BulkResult;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for archive/unarchive across both bank scopes (owner vs HEAD). */
class QuestionBankReviewServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final QuestionBankItemRepository itemRepository = mock(QuestionBankItemRepository.class);
    private final HeadDepartmentResolver resolver = mock(HeadDepartmentResolver.class);
    private final QuestionBankAccessPolicy accessPolicy = new QuestionBankAccessPolicy(resolver);
    private final QuestionBankReviewService service = new QuestionBankReviewService(
            userRepository, accessPolicy, itemRepository);

    @Test
    void lecturer_can_archive_own_private_item() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem item = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.archive(20L, 10L);

        assertThat(item.getStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);
        assertThat(item.getStatusBeforeArchive()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    void lecturer_cannot_archive_another_lecturers_item() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem item = item(5L, 1L, 21L, 10L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.archive(20L, 10L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void head_can_archive_head_bank_item_in_own_department() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem item = item(5L, 1L, null, 10L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.archive(30L, 10L);

        assertThat(item.getStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);
    }

    @Test
    void head_cannot_archive_a_lecturers_private_item() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem item = item(5L, 1L, 20L, 10L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.archive(30L, 10L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void archive_requires_active_state() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem item = item(5L, 1L, null, 10L, QuestionBankItem.STATUS_ARCHIVED);
        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.archive(30L, 10L))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void unarchive_restores_prior_active_status() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem item = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ARCHIVED);
        item.archive(); // remembers ACTIVE in statusBeforeArchive
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.unarchive(20L, 10L);

        assertThat(item.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(item.getStatusBeforeArchive()).isNull();
    }

    @Test
    void unarchive_legacy_null_status_before_archive_falls_back_to_active() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        // Legacy row archived before status_before_archive existed: NULL remembered status.
        QuestionBankItem item = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ARCHIVED);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.unarchive(20L, 10L);

        assertThat(item.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(item.getStatusBeforeArchive()).isNull();
    }

    @Test
    void unarchive_requires_archived_state() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem item = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.unarchive(20L, 10L))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void bulk_archive_counts_transitioned_and_skips_invalid() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem active = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ACTIVE);
        QuestionBankItem archived = item(5L, 1L, lecturer.getId(), 11L, QuestionBankItem.STATUS_ARCHIVED);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(active));
        when(itemRepository.findById(11L)).thenReturn(Optional.of(archived));
        // Item 12 does not exist → skipped.
        when(itemRepository.findById(12L)).thenReturn(Optional.empty());

        BulkResult result = service.archiveAll(20L, List.of(10L, 11L, 12L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(active.getStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);
    }

    @Test
    void bulk_unarchive_restores_active_and_skips_non_archived() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        QuestionBankItem archivable = item(5L, 1L, lecturer.getId(), 10L, QuestionBankItem.STATUS_ACTIVE);
        QuestionBankItem active = item(5L, 1L, lecturer.getId(), 11L, QuestionBankItem.STATUS_ACTIVE);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));
        when(itemRepository.findById(10L)).thenReturn(Optional.of(archivable));
        when(itemRepository.findById(11L)).thenReturn(Optional.of(active));

        service.archive(20L, 10L); // remembers ACTIVE
        BulkResult result = service.unarchiveAll(20L, List.of(10L, 11L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(archivable.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
    }

    @Test
    void bulk_empty_selection_returns_zero() {
        BulkResult result = service.archiveAll(30L, List.of());
        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isZero();
    }

    private static QuestionBankItem item(Long departmentId, Long subjectId, Long ownerId,
                                         Long id, String status) {
        QuestionBankItem item = new QuestionBankItem(
                departmentId, subjectId, ownerId, null, 20L,
                QuestionBankItem.TYPE_MCQ, status, "<p>Question</p>", null);
        setId(item, id);
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

    private static void setId(QuestionBankItem item, Long id) {
        try {
            Field idField = QuestionBankItem.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(item, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
