package com.ulp.features.classes.service;

import com.ulp.security.Role;
import com.ulp.entities.User;
import com.ulp.features.classes.dto.MemberDtos.MemberRow;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.EnrollmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test cho {@link ClassMembersService}. Mock {@link ClassesService} +
 * {@link EnrollmentRepository} de tap trung vao logic build MemberRow.
 *
 * <p>Bao phu chinh:
 * <ul>
 *   <li>Wave 1 Finding A — gradient assignment dung index theo thu tu enrollments
 *       (positions 0, 4, 5 — test wrap-around qua {@code Math.floorMod}).</li>
 *   <li>Empty list returns empty rows + total = 0.</li>
 * </ul>
 */
class ClassMembersServiceTest {

    private static final Long ANY_USER_ID = 42L;

    private static final String[] EXPECTED_GRADIENT_AT_INDEX = {
            "linear-gradient(135deg,#5E92F3,#1E88E5)", // 0
            "linear-gradient(135deg,#EC407A,#D81B60)", // 1
            "linear-gradient(135deg,#26A69A,#00897B)", // 2
            "linear-gradient(135deg,#FFA726,#FB8C00)", // 3
            "linear-gradient(135deg,#7E57C2,#5E35B1)"  // 4
    };

    private ClassesService classesService;
    private EnrollmentRepository enrollmentRepository;
    private ClassMembersService service;

    @BeforeEach
    void setUp() {
        classesService = mock(ClassesService.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        service = new ClassMembersService(classesService, enrollmentRepository);
    }

    @Test
    void list_for_class_assigns_gradient_by_index_with_floor_mod_wrap() {
        // Seed: 6 enrollments so we can verify positions 0, 4 (last in palette)
        // and 5 (must wrap back to index 0).
        ClassEntity clazz = newClass(11L, "TestClass");
        List<Enrollment> enrollments = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            enrollments.add(newEnrollment(100L + i, "User " + i, "u" + i + "@ulp.vn"));
        }

        when(classesService.getViewable(eq(11L), eq(ANY_USER_ID), eq(Role.LECTURER))).thenReturn(clazz);
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(11L, "ACTIVE"))
                .thenReturn(enrollments);
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(11L, "PENDING"))
                .thenReturn(List.of());

        ClassMembersService.ClassMembersView view = service.listForClass(11L, ANY_USER_ID, Role.LECTURER);
        List<MemberRow> rows = view.members();

        assertThat(rows).hasSize(6);
        assertThat(view.total()).isEqualTo(6);
        assertThat(view.pendingTotal()).isZero();

        // Position 0 → palette index 0
        assertThat(rows.get(0).avatarGradient()).isEqualTo(EXPECTED_GRADIENT_AT_INDEX[0]);
        // Position 4 → palette index 4 (last bucket before wrap)
        assertThat(rows.get(4).avatarGradient()).isEqualTo(EXPECTED_GRADIENT_AT_INDEX[4]);
        // Position 5 → floorMod(5, 5) = 0 → wraps to first gradient
        assertThat(rows.get(5).avatarGradient()).isEqualTo(EXPECTED_GRADIENT_AT_INDEX[0]);
    }

    @Test
    void list_for_class_returns_empty_rows_when_no_enrollments() {
        ClassEntity clazz = newClass(12L, "EmptyClass");
        when(classesService.getViewable(eq(12L), eq(ANY_USER_ID), eq(Role.LECTURER))).thenReturn(clazz);
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(12L, "ACTIVE"))
                .thenReturn(List.of());
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(12L, "PENDING"))
                .thenReturn(List.of());

        ClassMembersService.ClassMembersView view = service.listForClass(12L, ANY_USER_ID, Role.LECTURER);

        assertThat(view.members()).isEmpty();
        assertThat(view.total()).isZero();
        assertThat(view.pendingMembers()).isEmpty();
        assertThat(view.pendingTotal()).isZero();
        assertThat(view.clazz()).isSameAs(clazz);
    }

    @Test
    void list_for_class_preserves_user_fields_in_member_row() {
        ClassEntity clazz = newClass(13L, "FieldsClass");
        Enrollment e = newEnrollment(500L, "Nguyen Van A", "nva@ulp.vn");
        ReflectionTestUtils.setField(e, "joinedVia", "CODE");
        // Set phone on the underlying user
        ReflectionTestUtils.setField(e.getUser(), "phone", "0901234567");

        when(classesService.getViewable(eq(13L), eq(ANY_USER_ID), eq(Role.LECTURER))).thenReturn(clazz);
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(13L, "ACTIVE"))
                .thenReturn(List.of(e));
        when(enrollmentRepository.findAllByClassIdAndStatusOrderByJoinedAtDesc(13L, "PENDING"))
                .thenReturn(List.of());

        MemberRow row = service.listForClass(13L, ANY_USER_ID, Role.LECTURER).members().get(0);

        assertThat(row.userId()).isEqualTo(500L);
        assertThat(row.fullName()).isEqualTo("Nguyen Van A");
        assertThat(row.email()).isEqualTo("nva@ulp.vn");
        assertThat(row.phone()).isEqualTo("0901234567");
        assertThat(row.joinedVia()).isEqualTo("CODE");
        assertThat(row.avatarLabel()).isEqualTo("NA");
    }

    // ─────────── Helpers ───────────

    private static ClassEntity newClass(long id, String name) {
        ClassEntity c = new ClassEntity(name, 42L, 42L, null, null, null, 100);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private static Enrollment newEnrollment(long userId, String fullName, String email) {
        User u = new User() {};
        ReflectionTestUtils.setField(u, "id", userId);
        ReflectionTestUtils.setField(u, "email", email);
        ReflectionTestUtils.setField(u, "fullName", fullName);
        ReflectionTestUtils.setField(u, "passwordHash", "x");

        Enrollment e = new Enrollment() {};
        ReflectionTestUtils.setField(e, "user", u);
        ReflectionTestUtils.setField(e, "classId", 11L);
        ReflectionTestUtils.setField(e, "status", "ACTIVE");
        return e;
    }
}
