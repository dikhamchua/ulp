package com.ulp.features.classes.service;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.service.approval.ClassReviewNotifier;
import com.ulp.features.classes.service.codes.ClassCodeGenerator;
import com.ulp.features.classes.service.invites.InviteCodeService;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.service.SubjectService;
import com.ulp.features.subjects.service.SubjectValidationException;
import com.ulp.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Class department is stamped from subject, never from the lecturer. */
class ClassSubjectBindingTest {

    private static final Long LECTURER_ID = 42L;
    private static final Long LECTURER_DEPT = 1L;
    private static final Long SUBJECT_DEPT = 2L;

    private ClassRepository classRepository;
    private SubjectService subjectService;
    private ClassesService service;
    private Subject crossDeptSubject;

    @BeforeEach
    void setUp() {
        classRepository = mock(ClassRepository.class);
        ClassInviteCodeRepository inviteCodeRepository = mock(ClassInviteCodeRepository.class);
        ClassActivityWriter activityWriter = mock(ClassActivityWriter.class);
        ClassCodeGenerator codeGenerator = mock(ClassCodeGenerator.class);
        InviteCodeService inviteCodeService = mock(InviteCodeService.class);
        subjectService = mock(SubjectService.class);
        ClassReviewNotifier reviewNotifier = mock(ClassReviewNotifier.class);

        service = new ClassesService(classRepository, inviteCodeRepository, activityWriter,
                codeGenerator, inviteCodeService, subjectService, reviewNotifier);

        when(codeGenerator.generate()).thenReturn("ABC12");
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenAnswer(inv -> {
            ClassEntity e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 200L);
            return e;
        });
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        crossDeptSubject = new Subject(SUBJECT_DEPT, "ECO101", "Kinh tế", null, true, 1L);
        ReflectionTestUtils.setField(crossDeptSubject, "id", 77L);
    }

    @Test
    void create_stamps_department_from_subject_not_lecturer() {
        when(subjectService.requireActiveSubject(77L)).thenReturn(crossDeptSubject);

        ClassForm form = new ClassForm("Cross dept class", 77L, "d", null, null, 40);
        ClassEntity saved = service.create(form, LECTURER_ID);

        assertThat(saved.getSubjectId()).isEqualTo(77L);
        assertThat(saved.getDepartmentId()).isEqualTo(SUBJECT_DEPT);
        assertThat(saved.getDepartmentId()).isNotEqualTo(LECTURER_DEPT);
        assertThat(saved.getLecturerId()).isEqualTo(LECTURER_ID);
        assertThat(saved.getStatus()).isEqualTo(ClassEntity.STATUS_DRAFT);
    }

    @Test
    void create_rejects_missing_subject_without_insert() {
        when(subjectService.requireActiveSubject(null))
                .thenThrow(new SubjectValidationException("Vui lòng chọn môn học"));

        ClassForm form = new ClassForm("No subject", null, null, null, null, 40);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(SubjectValidationException.class);
        verify(classRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_rejects_inactive_subject_without_insert() {
        when(subjectService.requireActiveSubject(9L))
                .thenThrow(new SubjectValidationException("Môn học không tồn tại hoặc đã ngừng hoạt động"));

        ClassForm form = new ClassForm("Inactive", 9L, null, null, null, 40);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(SubjectValidationException.class);
        verify(classRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_restamps_subject_and_department() {
        ClassEntity entity = new ClassEntity("Old", LECTURER_ID, LECTURER_ID, null, null, null, 50);
        ReflectionTestUtils.setField(entity, "id", 9L);
        entity.setSubjectId(1L);
        entity.setDepartmentId(LECTURER_DEPT);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        Subject newSubject = new Subject(SUBJECT_DEPT, "MKT", "Marketing", null, true, 1L);
        ReflectionTestUtils.setField(newSubject, "id", 88L);
        when(subjectService.requireActiveSubject(88L)).thenReturn(newSubject);

        ClassForm form = new ClassForm("Renamed", 88L, "x", null, null, 50);
        service.update(9L, form, LECTURER_ID, Role.LECTURER);

        assertThat(entity.getName()).isEqualTo("Renamed");
        assertThat(entity.getSubjectId()).isEqualTo(88L);
        assertThat(entity.getDepartmentId()).isEqualTo(SUBJECT_DEPT);
    }
}
