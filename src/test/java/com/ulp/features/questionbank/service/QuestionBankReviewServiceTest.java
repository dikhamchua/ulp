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

/** Unit tests for HEAD review transitions on the department-scoped shared bank. */
class QuestionBankReviewServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final QuestionBankItemRepository itemRepository = mock(QuestionBankItemRepository.class);
    private final HeadDepartmentResolver resolver = mock(HeadDepartmentResolver.class);
    private final QuestionBankAccessPolicy accessPolicy = new QuestionBankAccessPolicy(resolver);
    private final QuestionBankReviewService service = new QuestionBankReviewService(
            userRepository, accessPolicy, itemRepository);

    @Test
    void lecturer_cannot_approve_question() {
        User lecturer = user(Role.LECTURER, 20L, 5L);
        when(userRepository.findById(20L)).thenReturn(Optional.of(lecturer));

        assertThatThrownBy(() -> service.approve(20L, 1L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void head_can_approve_review_item_in_own_department() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem item = new QuestionBankItem(5L, 1L, 20L,
                QuestionBankItem.TYPE_MCQ, QuestionBankItem.STATUS_REVIEW,
                "<p>Question</p>", null);
        setId(item, 10L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(item));

        service.approve(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(item.getReviewedBy()).isEqualTo(30L);
        assertThat(item.getApprovedAt()).isNotNull();
    }

    @Test
    void bulk_approve_counts_transitioned_and_skips_invalid() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        // Item 10 is in REVIEW → approvable; item 11 is APPROVED → skipped.
        QuestionBankItem reviewItem = item(5L, QuestionBankItem.STATUS_REVIEW, 10L);
        QuestionBankItem approvedItem = item(5L, QuestionBankItem.STATUS_APPROVED, 11L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(reviewItem));
        when(itemRepository.findByIdAndDepartmentId(11L, 5L)).thenReturn(Optional.of(approvedItem));
        // Item 12 does not exist in the department → skipped.
        when(itemRepository.findByIdAndDepartmentId(12L, 5L)).thenReturn(Optional.empty());

        BulkResult result = service.approveAll(30L, List.of(10L, 11L, 12L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(reviewItem.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(approvedItem.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    void bulk_reject_requires_review_state() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem reviewItem = item(5L, QuestionBankItem.STATUS_REVIEW, 10L);
        QuestionBankItem archivedItem = item(5L, QuestionBankItem.STATUS_ARCHIVED, 11L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(reviewItem));
        when(itemRepository.findByIdAndDepartmentId(11L, 5L)).thenReturn(Optional.of(archivedItem));

        BulkResult result = service.rejectAll(30L, List.of(10L, 11L), "note");

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(reviewItem.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REJECTED);
    }

    @Test
    void bulk_archive_skips_already_archived() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem approvedItem = item(5L, QuestionBankItem.STATUS_APPROVED, 10L);
        QuestionBankItem archivedItem = item(5L, QuestionBankItem.STATUS_ARCHIVED, 11L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(approvedItem));
        when(itemRepository.findByIdAndDepartmentId(11L, 5L)).thenReturn(Optional.of(archivedItem));

        BulkResult result = service.archiveAll(30L, List.of(10L, 11L), null);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(approvedItem.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);
    }

    @Test
    void unarchive_restores_status_before_archive() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        // Archive an APPROVED item, then unarchive: it must return to APPROVED.
        QuestionBankItem item = item(5L, QuestionBankItem.STATUS_APPROVED, 10L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(item));

        service.archive(30L, 10L, null);
        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_ARCHIVED);

        service.unarchive(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
        assertThat(item.getStatusBeforeArchive()).isNull();
        assertThat(item.getReviewedBy()).isEqualTo(30L);
    }

    @Test
    void unarchive_requires_archived_state() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        QuestionBankItem approvedItem = item(5L, QuestionBankItem.STATUS_APPROVED, 10L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(approvedItem));

        assertThatThrownBy(() -> service.unarchive(30L, 10L))
                .isInstanceOf(QuestionBankValidationException.class);
    }

    @Test
    void unarchive_legacy_null_falls_back_to_review() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        // Legacy row archived before status_before_archive existed: NULL remembered status.
        QuestionBankItem item = item(5L, QuestionBankItem.STATUS_ARCHIVED, 10L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(item));

        service.unarchive(30L, 10L);

        assertThat(item.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_REVIEW);
        assertThat(item.getStatusBeforeArchive()).isNull();
    }

    @Test
    void bulk_unarchive_counts_restored_and_skips_non_archived() {
        User head = user(Role.HEAD, 30L, 99L);
        Department department = department(5L, head.getId());
        // Archive item 10 first (remembers APPROVED); item 11 is APPROVED → skipped.
        QuestionBankItem archivable = item(5L, QuestionBankItem.STATUS_APPROVED, 10L);
        QuestionBankItem approvedItem = item(5L, QuestionBankItem.STATUS_APPROVED, 11L);

        when(userRepository.findById(30L)).thenReturn(Optional.of(head));
        when(resolver.resolve(head.getId())).thenReturn(Optional.of(department));
        when(itemRepository.findByIdAndDepartmentId(10L, 5L)).thenReturn(Optional.of(archivable));
        when(itemRepository.findByIdAndDepartmentId(11L, 5L)).thenReturn(Optional.of(approvedItem));

        service.archive(30L, 10L, null);

        BulkResult result = service.unarchiveAll(30L, List.of(10L, 11L));

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(archivable.getWorkflowStatus()).isEqualTo(QuestionBankItem.STATUS_APPROVED);
    }

    @Test
    void bulk_empty_selection_returns_zero() {
        // No repository/user interaction expected: dedupe short-circuits an empty list.
        BulkResult result = service.approveAll(30L, List.of());

        assertThat(result.succeeded()).isZero();
        assertThat(result.skipped()).isZero();
    }

    private static QuestionBankItem item(Long departmentId, String status, Long id) {
        QuestionBankItem item = new QuestionBankItem(departmentId, 1L, 20L,
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
