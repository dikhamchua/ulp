package com.ulp.classes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.dto.ClassesDtos.ClassForm;
import com.ulp.classes.dto.ClassesDtos.ClassRow;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.repository.ClassActivityRepository;
import com.ulp.classes.repository.ClassRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link ClassesService}. Mock toan bo repository + generator.
 * Bao phu: list-by-role, owner check, code collision retry (both branches),
 * activity write tren moi mutation, edge case 404/403.
 */
class ClassesServiceTest {

    private ClassRepository classRepository;
    private ClassActivityRepository activityRepository;
    private ClassCodeGenerator codeGenerator;
    private UserRepository userRepository;
    private ClassesService service;

    private User lecturer;
    private User otherLecturer;
    private User head;
    private User admin;
    private Principal lecturerPrincipal;
    private Principal otherLecturerPrincipal;
    private Principal headPrincipal;
    private Principal adminPrincipal;

    @BeforeEach
    void setUp() {
        classRepository = mock(ClassRepository.class);
        activityRepository = mock(ClassActivityRepository.class);
        codeGenerator = mock(ClassCodeGenerator.class);
        userRepository = mock(UserRepository.class);
        service = new ClassesService(classRepository, activityRepository, codeGenerator, userRepository, new ObjectMapper());

        lecturer = buildUser(42L, "lect@ulp.vn", Role.LECTURER);
        otherLecturer = buildUser(99L, "other@ulp.vn", Role.LECTURER);
        head = buildUser(7L, "head@ulp.vn", Role.HEAD);
        admin = buildUser(1L, "admin@ulp.vn", Role.ADMIN);

        lecturerPrincipal = () -> lecturer.getEmail();
        otherLecturerPrincipal = () -> otherLecturer.getEmail();
        headPrincipal = () -> head.getEmail();
        adminPrincipal = () -> admin.getEmail();

        when(userRepository.findByEmail(lecturer.getEmail())).thenReturn(Optional.of(lecturer));
        when(userRepository.findByEmail(otherLecturer.getEmail())).thenReturn(Optional.of(otherLecturer));
        when(userRepository.findByEmail(head.getEmail())).thenReturn(Optional.of(head));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
    }

    // ───────────────── List by role ─────────────────

    @Test
    void list_for_lecturer_filters_to_own_classes() {
        when(classRepository.findAllByLecturerIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(buildClass(1L, "Java", 42L)));

        List<ClassRow> rows = service.listForUser(lecturerPrincipal);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).name()).isEqualTo("Java");
        verify(classRepository, never()).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void list_for_head_returns_all() {
        when(classRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(buildClass(1L, "A", 42L), buildClass(2L, "B", 99L)));

        List<ClassRow> rows = service.listForUser(headPrincipal);

        assertThat(rows).hasSize(2);
        verify(classRepository, never()).findAllByLecturerIdOrderByCreatedAtDesc(any());
    }

    @Test
    void list_for_admin_returns_all() {
        when(classRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(buildClass(1L, "A", 42L)));

        List<ClassRow> rows = service.listForUser(adminPrincipal);

        assertThat(rows).hasSize(1);
    }

    @Test
    void list_returns_zero_stat_columns() {
        when(classRepository.findAllByLecturerIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(buildClass(1L, "X", 42L)));

        ClassRow row = service.listForUser(lecturerPrincipal).get(0);

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
        ClassEntity saved = service.create(form, lecturerPrincipal);

        assertThat(saved.getCode()).isEqualTo("NILXM");
        assertThat(saved.getLecturerId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo("UPCOMING");

        ArgumentCaptor<ClassActivity> ac = ArgumentCaptor.forClass(ClassActivity.class);
        verify(activityRepository).save(ac.capture());
        assertThat(ac.getValue().getType()).isEqualTo(ClassActivity.TYPE_CREATED);
        assertThat(ac.getValue().getCreatedBy()).isEqualTo(42L);
        assertThat(ac.getValue().getClassId()).isEqualTo(100L);
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
        ClassEntity saved = service.create(form, lecturerPrincipal);

        assertThat(saved.getCode()).isEqualTo("NILXM");
        verify(classRepository, times(2)).saveAndFlush(any(ClassEntity.class));
        verify(activityRepository).save(any(ClassActivity.class));
    }

    @Test
    void create_rethrows_non_code_collision_without_retry() {
        when(codeGenerator.generate()).thenReturn("NILXM");

        DataIntegrityViolationException other = new DataIntegrityViolationException(
                "Some other constraint",
                new RuntimeException("Cannot be null: classes.name"));
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenThrow(other);

        ClassForm form = new ClassForm("Java", "x", null, null, 100);

        assertThatThrownBy(() -> service.create(form, lecturerPrincipal))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(classRepository, times(1)).saveAndFlush(any(ClassEntity.class));
        verify(activityRepository, never()).save(any(ClassActivity.class));
    }

    @Test
    void create_throws_after_three_collisions() {
        when(codeGenerator.generate()).thenReturn("A", "B", "C");

        DataIntegrityViolationException collision = new DataIntegrityViolationException(
                "x",
                new RuntimeException("Duplicate entry for key 'uk_classes_code'"));
        when(classRepository.saveAndFlush(any(ClassEntity.class))).thenThrow(collision);

        ClassForm form = new ClassForm("Java", "x", null, null, 100);

        assertThatThrownBy(() -> service.create(form, lecturerPrincipal))
                .isInstanceOf(ClassCodeGenerationException.class);

        verify(classRepository, times(3)).saveAndFlush(any(ClassEntity.class));
        verify(activityRepository, never()).save(any(ClassActivity.class));
    }

    // ───────────────── Authz: owner check ─────────────────

    @Test
    void update_by_owning_lecturer_succeeds_and_writes_updated_activity() {
        ClassEntity entity = buildClass(9L, "Old name", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("New name", "desc", null, null, 50);
        service.update(9L, form, lecturerPrincipal);

        assertThat(entity.getName()).isEqualTo("New name");

        ArgumentCaptor<ClassActivity> ac = ArgumentCaptor.forClass(ClassActivity.class);
        verify(activityRepository).save(ac.capture());
        assertThat(ac.getValue().getType()).isEqualTo(ClassActivity.TYPE_UPDATED);
        assertThat(ac.getValue().getMetadata()).contains("Old name").contains("New name");
    }

    @Test
    void update_by_non_owning_lecturer_throws_403() {
        ClassEntity entity = buildClass(9L, "X", 42L); // owned by lecturer id=42
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassForm form = new ClassForm("Y", "", null, null, 50);

        assertThatThrownBy(() -> service.update(9L, form, otherLecturerPrincipal))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityRepository, never()).save(any(ClassActivity.class));
    }

    @Test
    void update_by_head_succeeds_for_any_class() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("Y", "", null, null, 50);
        service.update(9L, form, headPrincipal);

        assertThat(entity.getName()).isEqualTo("Y");
        verify(activityRepository).save(any(ClassActivity.class));
    }

    @Test
    void update_by_admin_succeeds_for_any_class() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassForm form = new ClassForm("Y", "", null, null, 50);
        service.update(9L, form, adminPrincipal);

        assertThat(entity.getName()).isEqualTo("Y");
    }

    @Test
    void update_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        ClassForm form = new ClassForm("X", "", null, null, 50);
        assertThatThrownBy(() -> service.update(999L, form, lecturerPrincipal))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Soft-delete ─────────────────

    @Test
    void soft_delete_by_owner_marks_deleted_and_writes_activity() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));
        when(classRepository.save(any(ClassEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.softDelete(9L, lecturerPrincipal);

        assertThat(entity.isDeleted()).isTrue();

        ArgumentCaptor<ClassActivity> ac = ArgumentCaptor.forClass(ClassActivity.class);
        verify(activityRepository).save(ac.capture());
        assertThat(ac.getValue().getType()).isEqualTo(ClassActivity.TYPE_DELETED);
    }

    @Test
    void soft_delete_by_non_owner_throws_403() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.softDelete(9L, otherLecturerPrincipal))
                .isInstanceOf(AccessDeniedException.class);

        verify(classRepository, never()).save(any(ClassEntity.class));
        verify(activityRepository, never()).save(any(ClassActivity.class));
    }

    @Test
    void soft_delete_throws_entity_not_found_when_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.softDelete(999L, lecturerPrincipal))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── getEditable ─────────────────

    @Test
    void get_editable_returns_entity_for_owner() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        ClassEntity result = service.getEditable(9L, lecturerPrincipal);

        assertThat(result).isSameAs(entity);
    }

    @Test
    void get_editable_throws_403_for_non_owner() {
        ClassEntity entity = buildClass(9L, "X", 42L);
        when(classRepository.findById(9L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getEditable(9L, otherLecturerPrincipal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void get_editable_throws_not_found_for_missing() {
        when(classRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEditable(999L, lecturerPrincipal))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ───────────────── Helpers ─────────────────

    private static User buildUser(long id, String email, Role role) {
        User u = new User() {}; // protected constructor — anonymous subclass
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "email", email);
        ReflectionTestUtils.setField(u, "fullName", email);
        ReflectionTestUtils.setField(u, "role", role);
        ReflectionTestUtils.setField(u, "passwordHash", "x");
        return u;
    }

    private static ClassEntity buildClass(long id, String name, long lecturerId) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
