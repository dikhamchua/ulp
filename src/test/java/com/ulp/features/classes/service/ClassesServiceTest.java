package com.ulp.features.classes.service;

import com.ulp.security.Role;
import com.ulp.features.classes.dto.ClassesDtos.ClassForm;
import com.ulp.features.classes.dto.ClassesDtos.ClassRow;
import com.ulp.entities.ClassActivity;
import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.repository.ClassRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link ClassesService}. Mock toan bo repository + generator.
 * Bao phu: list-by-role, owner check, code collision retry (both branches),
 * activity write tren moi mutation, edge case 404/403.
 *
 * <p>Wave 2 refactor (perf-services-cache-and-principal): service no longer
 * resolves the caller via {@code Principal}; tests pass {@code (userId, role)}
 * directly. {@code UserRepository} is no longer injected.
 */
class ClassesServiceTest {

    private static final Long LECTURER_ID = 42L;
    private static final Long OTHER_LECTURER_ID = 99L;
    private static final Long HEAD_ID = 7L;
    private static final Long ADMIN_ID = 1L;

    private ClassRepository classRepository;
    private ClassActivityWriter activityWriter;
    private ClassCodeGenerator codeGenerator;
    private InviteCodeService inviteCodeService;
    private ClassesService service;

    @BeforeEach
    void setUp() {
        classRepository = mock(ClassRepository.class);
        activityWriter = mock(ClassActivityWriter.class);
        codeGenerator = mock(ClassCodeGenerator.class);
        inviteCodeService = mock(InviteCodeService.class);
        service = new ClassesService(classRepository, activityWriter, codeGenerator,
                inviteCodeService);
    }

    // ───────────────── List by role ─────────────────

    @Test
    void list_for_lecturer_filters_to_own_classes() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllByLecturerId(eq(LECTURER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "Java", LECTURER_ID)), pageable, 1));

        Page<ClassRow> rows = service.listForUser(LECTURER_ID, Role.LECTURER, pageable);

        assertThat(rows.getContent()).hasSize(1);
        assertThat(rows.getContent().get(0).name()).isEqualTo("Java");
        verify(classRepository, never()).findAllBy(any(Pageable.class));
    }

    @Test
    void list_for_head_returns_all() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(buildClass(1L, "A", LECTURER_ID), buildClass(2L, "B", OTHER_LECTURER_ID)),
                        pageable, 2));

        Page<ClassRow> rows = service.listForUser(HEAD_ID, Role.HEAD, pageable);

        assertThat(rows.getContent()).hasSize(2);
        verify(classRepository, never()).findAllByLecturerId(any(), any(Pageable.class));
    }

    @Test
    void list_for_admin_returns_all() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "A", LECTURER_ID)), pageable, 1));

        Page<ClassRow> rows = service.listForUser(ADMIN_ID, Role.ADMIN, pageable);

        assertThat(rows.getContent()).hasSize(1);
    }

    @Test
    void list_returns_zero_stat_columns() {
        Pageable pageable = PageRequest.of(0, 20);
        when(classRepository.findAllByLecturerId(eq(LECTURER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(buildClass(1L, "X", LECTURER_ID)), pageable, 1));

        ClassRow row = service.listForUser(LECTURER_ID, Role.LECTURER, pageable).getContent().get(0);

        assertThat(row.studentCount()).isZero();
        assertThat(row.lectureCount()).isZero();
        assertThat(row.assignmentCount()).isZero();
        assertThat(row.materialCount()).isZero();
    }

    // ───────────────── Create + collision retry ─────────────────

    @Test
    void create_persists_and_writes_created_activity() {
        when(codeGenerator.generate()).thenReturn("NILXM");
        when(classRepository.saveAndFlush(any(ClassEntity.class)))
                .thenAnswer(inv -> {
                    ClassEntity e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", 100L);
                    return e;
                });

        ClassForm form = new ClassForm("Java", "Khoá nhập môn",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31), 50);
        ClassEntity saved = service.create(form, LECTURER_ID);

        assertThat(saved.getCode()).isEqualTo("NILXM");
        assertThat(saved.getLecturerId()).isEqualTo(LECTURER_ID);
        assertThat(saved.getStatus()).isEqualTo("UPCOMING");

        verify(activityWriter).write(eq(100L), eq(ClassActivity.TYPE_CREATED),
                eq("Tạo lớp Java"), eq(LECTURER_ID));

        // Verify the default CODE + LINK tokens are provisioned
        // atomically as part of the create flow.
        verify(inviteCodeService, times(1)).provisionDefaults(100L, LECTURER_ID);
    }

    @Test
    void create_retries_when_code_collision_then_succeeds() {
        when(codeGenerator.generate()).thenReturn("DUPED", "NILXM");

        DataIntegrityViolationException collision = new DataIntegrityViolationException(
                "Duplicate key",
                new RuntimeException("Duplicate entry 'DUPED' for key 'classes.uk_classes_code'"));
        when(classRepository.saveAndFlush(any(ClassEntity.class)))
                .thenThrow(collision)
                .thenAnswer(inv -> {
                    ClassEntity e = inv.getArgument(0);
                    ReflectionTestUtils.setField(e, "id", 101L);
                    return e;
                });

        ClassForm form = new ClassForm("Java", "x", null, null, 100);
        ClassEntity saved = service.create(form, LECTURER_ID);

        assertThat(saved.getCode()).isEqualTo("NILXM");
        verify(classRepository, times(2)).saveAndFlush(any(ClassEntity.class));
        verify(activityWriter).write(eq(101L), eq(ClassActivity.TYPE_CREATED),
                any(), eq(LECTURER_ID));
    }

    @Test
    void create_rethrows_non_code_collision_without_retry() {
        when(codeGenerator.generate()).thenReturn("NILXM");

        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "Some other constraint",
                new RuntimeException("Cannot be null: classes.name"));
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenThrow(other);

        ClassForm form = new ClassForm("Java", "x", null, null, 100);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(classRepository, times(1)).saveAndFlush(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
        verify(inviteCodeService, never()).provisionDefaults(any(), any());
    }

    @Test
    void create_throws_after_three_collisions() {
        when(codeGenerator.generate()).thenReturn("A", "B", "C");

        DataIntegrityViolationException collision = new DataIntegrityViolationException(
                "x",
                new RuntimeException("Duplicate entry for key 'uk_classes_code'"));
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenThrow(collision);

        ClassForm form = new ClassForm("Java", "x", null, null, 100);

        assertThatThrownBy(() -> service.create(form, LECTURER_ID))
                .isInstanceOf(ClassCodeGenerationException.class);

        verify(classRepository, times(3)).saveAndFlush(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
        verify(inviteCodeService, never()).provisionDefaults(any(), any());
    }

    // ───────────────── Authz: owner check ─────────────────

    @Test
    void update_by_owning_lecturer_succeeds_and_writes_updated_activity() {
        ClassEntity entity = buildClass(9L, "Old name", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("New name", "desc", null, null, 50);
        service.update(9L, form, LECTURER_ID, Role.LECTURER);

        assertThat(entity.getName()).isEqualTo("New name");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCap =
                ArgumentCaptor.forClass(Map.class);
        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_UPDATED),
                eq("Cập nhật lớp New name"), metadataCap.capture(), eq(LECTURER_ID));

        Map<String, Object> diff = metadataCap.getValue();
        assertThat(diff).containsKey("old").containsKey("new");
        @SuppressWarnings("unchecked")
        Map<String, Object> oldState = (Map<String, Object>) diff.get("old");
        @SuppressWarnings("unchecked")
        Map<String, Object> newState = (Map<String, Object>) diff.get("new");
        assertThat(oldState).containsEntry("name", "Old name");
        assertThat(newState).containsEntry("name", "New name");
    }

    @Test
    void update_by_non_owning_lecturer_throws_403() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID); // owned by lecturer id=42
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassForm form = new ClassForm("Y", "", null, null, 50);

        assertThatThrownBy(() -> service.update(9L, form, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void update_by_head_succeeds_for_any_class() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("Y", "", null, null, 50);
        service.update(9L, form, HEAD_ID, Role.HEAD);

        assertThat(entity.getName()).isEqualTo("Y");
        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_UPDATED),
                any(), any(Map.class), eq(HEAD_ID));
    }

    @Test
    void update_by_admin_succeeds_for_any_class() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("Y", "", null, null, 50);
        service.update(9L, form, ADMIN_ID, Role.ADMIN);

        assertThat(entity.getName()).isEqualTo("Y");
    }

    @Test
    void update_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        ClassForm form = new ClassForm("X", "", null, null, 50);
        assertThatThrownBy(() -> service.update(999L, form, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Soft-delete ─────────────────

    @Test
    void soft_delete_by_owner_marks_deleted_and_writes_activity() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.softDelete(9L, LECTURER_ID, Role.LECTURER);

        assertThat(entity.isDeleted()).isTrue();

        verify(activityWriter).write(eq(9L), eq(ClassActivity.TYPE_DELETED),
                eq("Xoá lớp X"), eq(LECTURER_ID));
    }

    @Test
    void soft_delete_by_non_owner_throws_403() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.softDelete(9L, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void soft_delete_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.softDelete(999L, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── getEditable ─────────────────

    @Test
    void get_editable_returns_entity_for_owner() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassEntity result = service.getEditable(9L, LECTURER_ID, Role.LECTURER);

        assertThat(result).isSameAs(entity);
    }

    @Test
    void get_editable_throws_403_for_non_owner() {
        ClassEntity entity = buildClass(9L, "X", LECTURER_ID);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getEditable(9L, OTHER_LECTURER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void get_editable_throws_not_found_for_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEditable(999L, LECTURER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Helpers ─────────────────

    private static ClassEntity buildClass(long id, String name, long lecturerId) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
