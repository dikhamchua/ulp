package com.ulp.features.subjects;

import com.ulp.entities.Department;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectForm;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectOption;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectRow;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectActivityRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.features.subjects.service.SubjectAuditWriter;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for department-scoped subject catalog rules. */
class SubjectServiceTest {

    private static final Long HEAD_ID = 7L;
    private static final Long ADMIN_ID = 1L;
    private static final Long DEPT_A = 10L;
    private static final Long DEPT_B = 20L;

    private SubjectRepository subjectRepository;
    private SubjectActivityRepository activityRepository;
    private DepartmentRepository departmentRepository;
    private HeadDepartmentResolver headDepartmentResolver;
    private SubjectAuditWriter auditWriter;
    private SubjectService service;
    private Department deptA;

    @BeforeEach
    void setUp() {
        subjectRepository = mock(SubjectRepository.class);
        activityRepository = mock(SubjectActivityRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        headDepartmentResolver = mock(HeadDepartmentResolver.class);
        auditWriter = mock(SubjectAuditWriter.class);
        service = new SubjectService(
                subjectRepository, activityRepository, departmentRepository,
                headDepartmentResolver, auditWriter);

        deptA = new Department("CNTT", "CNTT", null, true);
        ReflectionTestUtils.setField(deptA, "id", DEPT_A);
        when(headDepartmentResolver.resolve(HEAD_ID)).thenReturn(Optional.of(deptA));
    }

    @Test
    void head_create_stamps_own_department() {
        when(subjectRepository.existsByDepartmentIdAndCodeIgnoreCase(DEPT_A, "PRJ301"))
                .thenReturn(false);
        when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> {
            Subject s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 100L);
            return s;
        });

        SubjectForm form = new SubjectForm("prj301", "Lập trình Java", null, true, DEPT_B);
        String title = service.create(form, HEAD_ID, Role.HEAD);

        assertThat(title).isEqualTo("Lập trình Java");
        verify(subjectRepository).save(org.mockito.ArgumentMatchers.argThat(s ->
                DEPT_A.equals(s.getDepartmentId())
                        && "PRJ301".equals(s.getCode())
                        && s.isActive()));
    }

    @Test
    void head_cannot_mutate_other_department_subject() {
        Subject other = new Subject(DEPT_B, "ECO101", "Kinh tế", null, true, ADMIN_ID);
        ReflectionTestUtils.setField(other, "id", 5L);
        when(subjectRepository.findById(5L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.softDelete(5L, HEAD_ID, Role.HEAD))
                .isInstanceOf(AccessDeniedException.class);
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void head_without_department_lists_empty() {
        when(headDepartmentResolver.resolve(HEAD_ID)).thenReturn(Optional.empty());

        List<SubjectRow> rows = service.listForActor(HEAD_ID, Role.HEAD);

        assertThat(rows).isEmpty();
        assertThat(service.isEmptyDepartmentForHead(HEAD_ID)).isTrue();
    }

    @Test
    void admin_create_requires_department() {
        SubjectForm form = new SubjectForm("X", "Y", null, true, null);
        assertThatThrownBy(() -> service.create(form, ADMIN_ID, Role.ADMIN))
                .isInstanceOf(SubjectValidationException.class);
    }

    @Test
    void admin_create_for_chosen_department() {
        when(departmentRepository.existsById(DEPT_B)).thenReturn(true);
        when(subjectRepository.existsByDepartmentIdAndCodeIgnoreCase(DEPT_B, "MKT101"))
                .thenReturn(false);
        when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> inv.getArgument(0));

        SubjectForm form = new SubjectForm("mkt101", "Marketing", "d", true, DEPT_B);
        service.create(form, ADMIN_ID, Role.ADMIN);

        verify(subjectRepository).save(org.mockito.ArgumentMatchers.argThat(s ->
                DEPT_B.equals(s.getDepartmentId()) && "MKT101".equals(s.getCode())));
    }

    @Test
    void duplicate_code_rejected() {
        when(subjectRepository.existsByDepartmentIdAndCodeIgnoreCase(DEPT_A, "PRJ301"))
                .thenReturn(true);

        SubjectForm form = new SubjectForm("PRJ301", "Dup", null, true, null);
        assertThatThrownBy(() -> service.create(form, HEAD_ID, Role.HEAD))
                .isInstanceOf(SubjectValidationException.class);
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void list_active_options_excludes_inactive() {
        Subject active = new Subject(DEPT_A, "PRJ301", "Java", null, true, 1L);
        ReflectionTestUtils.setField(active, "id", 1L);
        when(subjectRepository.findAllByActiveTrueOrderByCodeAsc()).thenReturn(List.of(active));
        when(departmentRepository.findById(DEPT_A)).thenReturn(Optional.of(deptA));

        List<SubjectOption> options = service.listActiveOptions();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).label()).contains("[CNTT]").contains("PRJ301");
        // Repository method already filters active — service does not re-add inactive.
        verify(subjectRepository).findAllByActiveTrueOrderByCodeAsc();
    }

    @Test
    void require_active_subject_rejects_null() {
        assertThatThrownBy(() -> service.requireActiveSubject(null))
                .isInstanceOf(SubjectValidationException.class);
    }

    @Test
    void require_active_subject_rejects_missing() {
        when(subjectRepository.findActiveById(eq(99L))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireActiveSubject(99L))
                .isInstanceOf(SubjectValidationException.class);
    }
}
