package com.ulp.classes.imports;

import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.EnrollmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RowValidator}. Each status code is exercised at least
 * once. UserRepository and EnrollmentRepository are mocked.
 */
class RowValidatorTest {

    private static final Long CLASS_ID = 7L;

    private UserRepository userRepository;
    private EnrollmentRepository enrollmentRepository;
    private RowValidator validator;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        validator = new RowValidator(userRepository, enrollmentRepository);

        // Default: nothing matches; individual tests override per-email.
        lenient().when(userRepository.findByEmailIgnoreCase(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        lenient().when(enrollmentRepository.findByUserIdAndClassId(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
    }

    @Test
    void missing_required_when_both_email_and_mssv_blank() {
        ImportRow row = run(new ExcelParser.RawRow(2, "", "", "Alice", "0900000001"));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.MISSING_REQUIRED);
    }

    @Test
    void invalid_email_when_pattern_mismatch() {
        ImportRow row = run(new ExcelParser.RawRow(2, "not-an-email", "SV0001", "Alice", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.INVALID_EMAIL);
    }

    @Test
    void invalid_student_id_when_pattern_mismatch() {
        ImportRow row = run(new ExcelParser.RawRow(2, "", "1!", "Alice", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.INVALID_STUDENT_ID);
    }

    @Test
    void user_not_found_when_email_does_not_match_any_user() {
        ImportRow row = run(new ExcelParser.RawRow(2, "ghost@ulp.vn", "SV0001", "Ghost", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.USER_NOT_FOUND);
    }

    @Test
    void mssv_only_row_returns_user_not_found_with_hint_about_email_column() {
        // V1 cannot resolve users by MSSV (User entity has no student_id
        // column). A row with only an MSSV must surface a lecturer-facing
        // hint pointing at the missing email column.
        ImportRow row = run(new ExcelParser.RawRow(2, null, "HE181234", "Alice", ""));

        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.USER_NOT_FOUND);
        assertThat(row.getErrorDetail()).isNotNull();
        assertThat(row.getErrorDetail().toLowerCase()).contains("email");
    }

    @Test
    void not_a_student_when_user_role_is_lecturer() {
        User lecturer = stubUser(50L, "lect@ulp.vn", Role.LECTURER, true, false);
        when(userRepository.findByEmailIgnoreCase("lect@ulp.vn")).thenReturn(Optional.of(lecturer));

        ImportRow row = run(new ExcelParser.RawRow(2, "lect@ulp.vn", "SV0001", "Lecturer", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.NOT_A_STUDENT);
    }

    @Test
    void user_inactive_when_user_is_locked() {
        User locked = stubUser(60L, "locked@ulp.vn", Role.STUDENT, true, true);
        when(userRepository.findByEmailIgnoreCase("locked@ulp.vn")).thenReturn(Optional.of(locked));

        ImportRow row = run(new ExcelParser.RawRow(2, "locked@ulp.vn", "SV0001", "Locked", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.USER_INACTIVE);
    }

    @Test
    void user_inactive_when_user_is_deactivated() {
        User off = stubUser(61L, "off@ulp.vn", Role.STUDENT, false, false);
        when(userRepository.findByEmailIgnoreCase("off@ulp.vn")).thenReturn(Optional.of(off));

        ImportRow row = run(new ExcelParser.RawRow(2, "off@ulp.vn", "SV0001", "Off", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.USER_INACTIVE);
    }

    @Test
    void duplicate_in_class_when_active_enrollment_already_exists() {
        User student = stubUser(70L, "active@ulp.vn", Role.STUDENT, true, false);
        when(userRepository.findByEmailIgnoreCase("active@ulp.vn")).thenReturn(Optional.of(student));
        Enrollment e = newEnrollment(student, "ACTIVE");
        when(enrollmentRepository.findByUserIdAndClassId(70L, CLASS_ID)).thenReturn(Optional.of(e));

        ImportRow row = run(new ExcelParser.RawRow(2, "active@ulp.vn", "SV0001", "Active", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.DUPLICATE_IN_CLASS);
        assertThat(row.getUserId()).isEqualTo(70L);
    }

    @Test
    void re_enroll_when_existing_enrollment_is_removed() {
        User student = stubUser(80L, "removed@ulp.vn", Role.STUDENT, true, false);
        when(userRepository.findByEmailIgnoreCase("removed@ulp.vn")).thenReturn(Optional.of(student));
        Enrollment e = newEnrollment(student, "REMOVED");
        when(enrollmentRepository.findByUserIdAndClassId(80L, CLASS_ID)).thenReturn(Optional.of(e));

        ImportRow row = run(new ExcelParser.RawRow(2, "removed@ulp.vn", "SV0001", "Returned", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.RE_ENROLL);
        assertThat(row.getUserId()).isEqualTo(80L);
    }

    @Test
    void ok_when_user_matches_and_no_existing_enrollment() {
        User student = stubUser(90L, "ok@ulp.vn", Role.STUDENT, true, false);
        when(userRepository.findByEmailIgnoreCase("ok@ulp.vn")).thenReturn(Optional.of(student));

        ImportRow row = run(new ExcelParser.RawRow(2, "ok@ulp.vn", "SV0001", "OK", ""));
        assertThat(row.getStatus()).isEqualTo(ImportRowStatus.OK);
        assertThat(row.getUserId()).isEqualTo(90L);
    }

    @Test
    void duplicate_in_file_when_email_appears_twice() {
        User student = stubUser(100L, "twice@ulp.vn", Role.STUDENT, true, false);
        when(userRepository.findByEmailIgnoreCase("twice@ulp.vn")).thenReturn(Optional.of(student));

        List<ImportRow> rows = validator.validate(List.of(
                new ExcelParser.RawRow(2, "twice@ulp.vn", "SV0001", "First",  ""),
                new ExcelParser.RawRow(3, "twice@ulp.vn", "SV0002", "Second", "")
        ), CLASS_ID);

        assertThat(rows.get(0).getStatus()).isEqualTo(ImportRowStatus.OK);
        assertThat(rows.get(1).getStatus()).isEqualTo(ImportRowStatus.DUPLICATE_IN_FILE);
    }

    // ─────────── Helpers ───────────

    private ImportRow run(ExcelParser.RawRow raw) {
        return validator.validate(List.of(raw), CLASS_ID).get(0);
    }

    private static User stubUser(long id, String email, Role role, boolean active, boolean locked) {
        User u = new User() {};
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "email", email);
        ReflectionTestUtils.setField(u, "fullName", email);
        ReflectionTestUtils.setField(u, "passwordHash", "x");
        ReflectionTestUtils.setField(u, "role", role);
        ReflectionTestUtils.setField(u, "active", active);
        ReflectionTestUtils.setField(u, "locked", locked);
        return u;
    }

    private static Enrollment newEnrollment(User user, String status) {
        Enrollment e = new Enrollment() {};
        ReflectionTestUtils.setField(e, "user", user);
        ReflectionTestUtils.setField(e, "classId", CLASS_ID);
        ReflectionTestUtils.setField(e, "status", status);
        return e;
    }
}
