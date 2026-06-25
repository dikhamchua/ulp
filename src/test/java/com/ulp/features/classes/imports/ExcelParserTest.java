package com.ulp.features.classes.imports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExcelParser}.
 *
 * <p>Each test builds a synthetic .xlsx in memory using POI and wraps it in a
 * {@link MockMultipartFile} so we never have to ship binary fixtures.
 */
class ExcelParserTest {

    private final ExcelParser parser = new ExcelParser();

    @Test
    void parse_returns_rows_when_file_is_valid() throws IOException {
        MultipartFile file = build(new String[]{"Email", "MSSV", "Họ tên", "SĐT"}, new String[][]{
                {"alice@ulp.vn", "SV0001", "Alice", "0900000001"},
                {"bob@ulp.vn",   "SV0002", "Bob",   "0900000002"}
        });

        ExcelParser.ParsedFile parsed = parser.parse(file);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).email()).isEqualTo("alice@ulp.vn");
        assertThat(parsed.rows().get(0).studentId()).isEqualTo("SV0001");
        assertThat(parsed.rows().get(0).fullName()).isEqualTo("Alice");
        assertThat(parsed.rows().get(0).phone()).isEqualTo("0900000001");
        assertThat(parsed.rows().get(1).email()).isEqualTo("bob@ulp.vn");
    }

    @Test
    void parse_accepts_aliased_and_diacritic_headers() throws IOException {
        // "E-mail" and "Mã sinh viên" must both resolve to canonical columns.
        MultipartFile file = build(new String[]{"E-mail", "Mã sinh viên", "Full Name", "Phone"}, new String[][]{
                {"x@ulp.vn", "SV9999", "Xena", "0900000099"}
        });

        ExcelParser.ParsedFile parsed = parser.parse(file);
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).email()).isEqualTo("x@ulp.vn");
        assertThat(parsed.rows().get(0).studentId()).isEqualTo("SV9999");
        assertThat(parsed.rows().get(0).fullName()).isEqualTo("Xena");
        assertThat(parsed.rows().get(0).phone()).isEqualTo("0900000099");
    }

    @Test
    void parse_rejects_non_excel_payload() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        MultipartFile file = new MockMultipartFile("file", "fake.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", png);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("không phải định dạng Excel");
    }

    @Test
    void parse_rejects_file_larger_than_2mb() {
        byte[] big = new byte[(int) (2 * 1024 * 1024 + 1)];
        // Make leading bytes valid ZIP magic so the size check runs before the magic check.
        big[0] = 0x50; big[1] = 0x4B; big[2] = 0x03; big[3] = 0x04;
        MultipartFile file = new MockMultipartFile("file", "huge.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", big);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("2 MB");
    }

    @Test
    void parse_rejects_file_with_too_many_data_rows() throws IOException {
        String[] header = {"Email", "MSSV"};
        String[][] data = new String[501][2];
        for (int i = 0; i < 501; i++) {
            data[i] = new String[]{"user" + i + "@ulp.vn", "SV" + String.format("%04d", i)};
        }
        MultipartFile file = build(header, data);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("500");
    }

    @Test
    void parse_rejects_file_without_email_or_student_id_columns() throws IOException {
        MultipartFile file = build(new String[]{"Họ tên", "Phone"}, new String[][]{
                {"Alice", "0900000001"}
        });

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("Email hoặc MSSV");
    }

    @Test
    void parse_skips_wholly_blank_rows() throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet();
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Email");
        header.createCell(1).setCellValue("MSSV");
        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue("alice@ulp.vn");
        r1.createCell(1).setCellValue("SV0001");
        // r2 intentionally left blank
        sheet.createRow(2);
        Row r3 = sheet.createRow(3);
        r3.createCell(0).setCellValue("bob@ulp.vn");
        r3.createCell(1).setCellValue("SV0002");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        MultipartFile file = new MockMultipartFile("file", "sample.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());

        ExcelParser.ParsedFile parsed = parser.parse(file);
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(parsed.rows().get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    void normalize_strips_vietnamese_diacritics_and_lowercases() {
        assertThat(ExcelParser.normalize("Họ Và Tên")).isEqualTo("ho va ten");
        assertThat(ExcelParser.normalize("MSSV")).isEqualTo("mssv");
        assertThat(ExcelParser.normalize("E-mail")).isEqualTo("e mail");
        assertThat(ExcelParser.normalize("Mã sinh viên")).isEqualTo("ma sinh vien");
        assertThat(ExcelParser.normalize("Số điện thoại")).isEqualTo("so dien thoai");
    }

    // ─────────── Helpers ───────────

    private static MultipartFile build(String[] header, String[][] data) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row h = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                h.createCell(i).setCellValue(header[i]);
            }
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "sample.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }
}
