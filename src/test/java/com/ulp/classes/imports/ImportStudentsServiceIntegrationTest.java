package com.ulp.classes.imports;

import com.ulp.auth.Role;
import com.ulp.auth.entity.User;
import com.ulp.auth.repository.UserRepository;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.ClassRepository;
import com.ulp.classes.repository.EnrollmentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the end-to-end import flow.
 *
 * <p>Uses seeded users from {@code V5__seed_test_users.sql} and
 * {@code V8__seed_fake_students.sql}. The test creates a fresh class owned by
 * {@code lecturer@ulp.edu.vn}, builds an in-memory Excel file, runs preview +
 * confirm, and verifies enrollments + activity logging.
 */
@SpringBootTest
@Transactional
class ImportStudentsServiceIntegrationTest {

    @Autowired private ImportStudentsService importStudentsService;
    @Autowired private ImportSessionStore sessionStore;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private User lecturer;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Import IT class", lecturer.getId(), "IMPIT");
    }

    @Test
    void full_flow_imports_ok_rows_and_skips_invalid() throws IOException {
        MultipartFile file = build(new String[]{"Email", "MSSV", "Họ tên", "SĐT"}, new String[][]{
                {"sv01@ulp.edu.vn", "SV0001", "Đỗ Khắc Nam", "0971761607"}, // OK — valid student
                {"sv02@ulp.edu.vn", "SV0002", "Trần Thu Hà", "0905123456"}, // OK — valid student
                {"ghost@nowhere.vn", "SV9999", "Ghost",       "0900000000"}, // USER_NOT_FOUND
                {"",                 "",       "Empty Row",   ""}            // MISSING_REQUIRED
        });

        ImportSession session = importStudentsService.previewUpload(
                file, clazz.getId(), lecturer.getId(), Role.LECTURER);

        assertThat(session.totalRows()).isEqualTo(4);
        assertThat(session.okCount()).isEqualTo(2);
        assertThat(session.errorCount()).isEqualTo(2);

        // Confirm with skipErrors=true so we proceed past the invalid rows.
        ImportResult result = importStudentsService.confirmImport(
                session.getId(), clazz.getId(), lecturer.getId(), Role.LECTURER,
                new ImportStudentsService.ImportOptions(true));

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.reactivated()).isZero();
        assertThat(result.skippedError()).isEqualTo(2);
        assertThat(result.failed()).isZero();

        long active = enrollmentRepository.countActiveByClassId(clazz.getId());
        assertThat(active).isEqualTo(2);

        // Session should have been deleted after confirm to prevent replay.
        assertThat(sessionStore.get(session.getId(), lecturer.getId())).isEmpty();
    }

    @Test
    void confirm_with_errors_and_skip_false_returns_without_writing() throws IOException {
        MultipartFile file = build(new String[]{"Email", "MSSV"}, new String[][]{
                {"sv03@ulp.edu.vn", "SV0003"},  // OK
                {"not-an-email",   "BAD"}      // INVALID_EMAIL
        });

        ImportSession session = importStudentsService.previewUpload(
                file, clazz.getId(), lecturer.getId(), Role.LECTURER);

        ImportResult result = importStudentsService.confirmImport(
                session.getId(), clazz.getId(), lecturer.getId(), Role.LECTURER,
                new ImportStudentsService.ImportOptions(false));

        // skipErrors=false + 1 error → service returns without writing.
        assertThat(result.imported()).isZero();
        assertThat(result.reactivated()).isZero();
        assertThat(result.skippedError()).isEqualTo(1);
        assertThat(enrollmentRepository.countActiveByClassId(clazz.getId())).isZero();
    }

    @Test
    void re_enroll_reactivates_previously_removed_enrollment() throws IOException {
        // Pre-seed: sv04 is in the class as REMOVED.
        User sv04 = userRepository.findByEmailIgnoreCase("sv04@ulp.edu.vn").orElseThrow();
        Enrollment seeded = new Enrollment(sv04, clazz.getId(), "MANUAL", null);
        seeded.markRemoved();
        enrollmentRepository.saveAndFlush(seeded);

        MultipartFile file = build(new String[]{"Email", "MSSV"}, new String[][]{
                {"sv04@ulp.edu.vn", "SV0004"}
        });

        ImportSession session = importStudentsService.previewUpload(
                file, clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(session.totalRows()).isEqualTo(1);
        assertThat(session.getRows().get(0).getStatus()).isEqualTo(ImportRowStatus.RE_ENROLL);

        ImportResult result = importStudentsService.confirmImport(
                session.getId(), clazz.getId(), lecturer.getId(), Role.LECTURER,
                new ImportStudentsService.ImportOptions(false));

        assertThat(result.reactivated()).isEqualTo(1);
        assertThat(result.imported()).isZero();

        Optional<Enrollment> reloaded = enrollmentRepository
                .findByUserIdAndClassId(sv04.getId(), clazz.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(Enrollment.STATUS_ACTIVE);
        assertThat(reloaded.get().getJoinedVia()).isEqualTo(ImportStudentsService.JOINED_VIA_IMPORT);
    }

    // ─────────── Helpers ───────────

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity e = new ClassEntity(name, lecturerId, lecturerId, null, null, null, 100);
        e.setCode(code);
        try {
            return classRepository.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            e.setCode(code + "x");
            return classRepository.saveAndFlush(e);
        }
    }

    private static MultipartFile build(String[] header, String[][] data) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) row.createCell(c).setCellValue(data[r][c]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "sample.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
