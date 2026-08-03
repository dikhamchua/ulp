package com.ulp.features.head.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Department;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.service.approval.ClassReviewNotifier;
import com.ulp.features.head.dto.HeadDtos.ApprovalQueueView;
import com.ulp.features.head.dto.HeadDtos.PendingClassRow;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HEAD queue badge when lecturer department differs from class department. */
class HeadClassApprovalBadgeTest {

    private static final Long HEAD_ID = 7L;
    private static final Long DEPT_ID = 10L;

    private HeadDepartmentResolver resolver;
    private ClassRepository classRepository;
    private UserRepository userRepository;
    private SubjectRepository subjectRepository;
    private HeadClassApprovalService service;

    @BeforeEach
    void setUp() {
        resolver = mock(HeadDepartmentResolver.class);
        classRepository = mock(ClassRepository.class);
        userRepository = mock(UserRepository.class);
        subjectRepository = mock(SubjectRepository.class);
        service = new HeadClassApprovalService(
                resolver, classRepository, userRepository, subjectRepository,
                mock(ClassReviewNotifier.class));

        Department dept = new Department("CNTT", "CNTT", null, true);
        ReflectionTestUtils.setField(dept, "id", DEPT_ID);
        when(resolver.resolve(HEAD_ID)).thenReturn(Optional.of(dept));
    }

    @Test
    void badge_when_lecturer_department_differs() {
        ClassEntity draft = draftClass(1L, 42L, DEPT_ID, 55L);
        User lecturer = user(42L, 99L, "GV khác");
        Subject subject = subject(55L, "Java");

        when(classRepository.findAllByDepartmentIdAndStatusOrderByCreatedAtDesc(
                eq(DEPT_ID), eq(ClassEntity.STATUS_DRAFT))).thenReturn(List.of(draft));
        when(userRepository.findById(42L)).thenReturn(Optional.of(lecturer));
        when(subjectRepository.findById(55L)).thenReturn(Optional.of(subject));

        ApprovalQueueView view = service.load(HEAD_ID);

        PendingClassRow row = view.pendingClasses().get(0);
        assertThat(row.crossDepartmentLecturer()).isTrue();
        assertThat(row.subjectTitle()).isEqualTo("Java");
    }

    @Test
    void no_badge_when_same_department() {
        ClassEntity draft = draftClass(1L, 42L, DEPT_ID, 55L);
        User lecturer = user(42L, DEPT_ID, "GV cùng");
        Subject subject = subject(55L, "Java");

        when(classRepository.findAllByDepartmentIdAndStatusOrderByCreatedAtDesc(
                any(), any())).thenReturn(List.of(draft));
        when(userRepository.findById(42L)).thenReturn(Optional.of(lecturer));
        when(subjectRepository.findById(55L)).thenReturn(Optional.of(subject));

        PendingClassRow row = service.load(HEAD_ID).pendingClasses().get(0);

        assertThat(row.crossDepartmentLecturer()).isFalse();
    }

    @Test
    void badge_when_lecturer_has_null_department() {
        ClassEntity draft = draftClass(1L, 42L, DEPT_ID, null);
        User lecturer = user(42L, null, "GV bare");

        when(classRepository.findAllByDepartmentIdAndStatusOrderByCreatedAtDesc(
                any(), any())).thenReturn(List.of(draft));
        when(userRepository.findById(42L)).thenReturn(Optional.of(lecturer));

        PendingClassRow row = service.load(HEAD_ID).pendingClasses().get(0);

        assertThat(row.crossDepartmentLecturer()).isTrue();
        assertThat(row.subjectTitle()).isNull();
    }

    @Test
    void isCrossDepartmentLecturer_helper() {
        assertThat(HeadClassApprovalService.isCrossDepartmentLecturer(null, 1L)).isTrue();
        assertThat(HeadClassApprovalService.isCrossDepartmentLecturer(2L, 1L)).isTrue();
        assertThat(HeadClassApprovalService.isCrossDepartmentLecturer(1L, 1L)).isFalse();
        assertThat(HeadClassApprovalService.isCrossDepartmentLecturer(1L, null)).isFalse();
    }

    private static ClassEntity draftClass(Long id, Long lecturerId, Long deptId, Long subjectId) {
        ClassEntity c = new ClassEntity("Draft", lecturerId, lecturerId, null, null, null, 30);
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "createdAt", LocalDateTime.now());
        c.setCode("D" + id);
        c.setDepartmentId(deptId);
        c.setSubjectId(subjectId);
        return c;
    }

    private static User user(Long id, Long deptId, String name) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getFullName()).thenReturn(name);
        when(u.getDepartmentId()).thenReturn(deptId);
        when(u.getRole()).thenReturn(Role.LECTURER);
        return u;
    }

    private static Subject subject(Long id, String title) {
        Subject s = new Subject(DEPT_ID, "C" + id, title, null, true, 1L);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }
}
