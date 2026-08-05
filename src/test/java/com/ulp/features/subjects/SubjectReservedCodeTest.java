package com.ulp.features.subjects;

import com.ulp.entities.Department;
import com.ulp.features.admin.departments.repository.DepartmentRepository;
import com.ulp.features.head.service.HeadDepartmentResolver;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectForm;
import com.ulp.features.subjects.dto.SubjectDtos.SubjectOption;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.repository.SubjectActivityRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import com.ulp.features.subjects.service.SubjectAuditWriter;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retired UNASSIGNED placeholder must never come back through subject CRUD.
 *
 * <p>V50 soft-deleted it, which nulls {@code live_code} and so frees the
 * {@code (department_id,'UNASSIGNED')} slot on {@code uk_subjects_dept_live_code}.
 * The database therefore no longer blocks a duplicate — these guards do.
 *
 * <p>Every rejection is asserted by a never-write on the repository, not by an
 * exception alone: an exception thrown after a save would still leave the row.
 */
class SubjectReservedCodeTest {

    private static final Long HEAD_ID = 7L;
    private static final Long DEPT_A = 10L;

    private SubjectRepository subjectRepository;
    private DepartmentRepository departmentRepository;
    private SubjectAuditWriter auditWriter;
    private SubjectService service;
    private Department deptA;

    @BeforeEach
    void setUp() {
        subjectRepository = mock(SubjectRepository.class);
        SubjectActivityRepository activityRepository = mock(SubjectActivityRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        HeadDepartmentResolver headDepartmentResolver = mock(HeadDepartmentResolver.class);
        auditWriter = mock(SubjectAuditWriter.class);
        service = new SubjectService(
                subjectRepository, activityRepository, departmentRepository,
                headDepartmentResolver, auditWriter);

        deptA = new Department("CNTT", "CNTT", null, true);
        ReflectionTestUtils.setField(deptA, "id", DEPT_A);
        when(headDepartmentResolver.resolve(HEAD_ID)).thenReturn(Optional.of(deptA));
    }

    private Subject placeholder(boolean active) {
        Subject s = new Subject(DEPT_A, Subject.CODE_UNASSIGNED, "Chưa phân môn", null, active, 1L);
        ReflectionTestUtils.setField(s, "id", 3L);
        return s;
    }

    @Test
    void create_rejects_reserved_code_without_insert() {
        SubjectForm form = new SubjectForm(
                Subject.CODE_UNASSIGNED, "Chưa phân môn", null, true, null);

        assertThatThrownBy(() -> service.create(form, HEAD_ID, Role.HEAD))
                .isInstanceOf(SubjectValidationException.class);
        verify(subjectRepository, never()).save(any());
        verify(auditWriter, never()).write(any(), any(), any(), any());
    }

    @Test
    void create_rejects_reserved_code_case_insensitively() {
        SubjectForm form = new SubjectForm("unassigned", "Chưa phân môn", null, true, null);

        assertThatThrownBy(() -> service.create(form, HEAD_ID, Role.HEAD))
                .isInstanceOf(SubjectValidationException.class);
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void update_rejects_rename_into_reserved_code_without_write() {
        Subject real = new Subject(DEPT_A, "PRJ301", "Java", null, true, 1L);
        ReflectionTestUtils.setField(real, "id", 4L);
        when(subjectRepository.findById(4L)).thenReturn(Optional.of(real));

        SubjectForm form = new SubjectForm("UNASSIGNED", "Đổi tên", null, true, null);

        assertThatThrownBy(() -> service.update(4L, form, HEAD_ID, Role.HEAD))
                .isInstanceOf(SubjectValidationException.class);
        verify(subjectRepository, never()).save(any());
        // The in-memory entity must be untouched too, not just the repository.
        assertThat(real.getCode()).isEqualTo("PRJ301");
    }

    @Test
    void toggle_active_cannot_activate_a_reserved_code_subject() {
        Subject ph = placeholder(false);
        when(subjectRepository.findById(3L)).thenReturn(Optional.of(ph));

        assertThatThrownBy(() -> service.toggleActive(3L, HEAD_ID, Role.HEAD))
                .isInstanceOf(SubjectValidationException.class);
        verify(subjectRepository, never()).save(any());
        assertThat(ph.isActive()).isFalse();
    }

    @Test
    void toggle_active_can_still_hide_a_surviving_placeholder() {
        // V50 skips departments with no real subject, so an active placeholder may
        // survive. Hiding it must stay possible — only activation is blocked.
        Subject ph = placeholder(true);
        when(subjectRepository.findById(3L)).thenReturn(Optional.of(ph));

        boolean now = service.toggleActive(3L, HEAD_ID, Role.HEAD);

        assertThat(now).isFalse();
        verify(subjectRepository).save(ph);
    }

    @Test
    void active_options_never_contain_the_placeholder() {
        Subject real = new Subject(DEPT_A, "PRJ301", "Java", null, true, 1L);
        ReflectionTestUtils.setField(real, "id", 1L);
        Subject ph = placeholder(true);
        when(subjectRepository.findAllByActiveTrueOrderByCodeAsc())
                .thenReturn(List.of(ph, real));
        when(departmentRepository.findById(DEPT_A)).thenReturn(Optional.of(deptA));

        List<SubjectOption> options = service.listActiveOptions();

        assertThat(options).hasSize(1);
        assertThat(options.get(0).code()).isEqualTo("PRJ301");
        assertThat(options).noneMatch(o -> Subject.CODE_UNASSIGNED.equalsIgnoreCase(o.code()));
    }
}
