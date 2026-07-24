package com.ulp.features.questionbank.imports;

import com.ulp.features.questionbank.imports.QuestionBankImportParser.ParsedFile;
import com.ulp.features.questionbank.service.QuestionBankValidationException;
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

/** Unit tests for question bank Excel parsing. */
class QuestionBankImportParserTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final QuestionBankImportParser parser = new QuestionBankImportParser();

    @Test
    void parse_returns_rows_when_file_matches_template() throws IOException {
        MultipartFile file = build(new String[][]{
                {"Danh mục", "Loại câu hỏi", "Nội dung câu hỏi", "Giải thích",
                        "Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D", "Đáp án đúng"},
                {"Giải tích 1", "MCQ", "Đạo hàm của x^2 là gì?", "Áp dụng quy tắc lũy thừa",
                        "2x", "x", "x^2", "2", "A"}
        });

        ParsedFile parsed = parser.parse(file);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).categoryName()).isEqualTo("Giải tích 1");
        assertThat(parsed.rows().get(0).questionType()).isEqualTo("MCQ");
        assertThat(parsed.rows().get(0).optionValues()).containsExactly("2x", "x", "x^2", "2");
        assertThat(parsed.rows().get(0).correctAnswers()).isEqualTo("A");
    }

    @Test
    void parse_skips_wholly_blank_rows() throws IOException {
        MultipartFile file = build(new String[][]{
                {"Danh mục", "Loại câu hỏi", "Nội dung câu hỏi", "Giải thích",
                        "Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D", "Đáp án đúng"},
                {"Giải tích 1", "MCQ", "Câu 1", "", "A", "B", "", "", "A"},
                {"", "", "", "", "", "", "", "", ""},
                {"Giải tích 1", "MR", "Câu 2", "", "A", "B", "C", "", "A,C"}
        });

        ParsedFile parsed = parser.parse(file);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(parsed.rows().get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    void parse_rejects_non_excel_payload() {
        MultipartFile file = new MockMultipartFile("file", "bank.xlsx", XLSX_MIME, "not excel".getBytes());

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(QuestionBankValidationException.class)
                .hasMessageContaining("Excel hợp lệ");
    }

    @Test
    void parse_rejects_missing_required_headers() throws IOException {
        MultipartFile file = build(new String[][]{
                {"Danh mục", "Nội dung câu hỏi", "Đáp án A", "Đáp án đúng"},
                {"Giải tích 1", "Câu 1", "A", "A"}
        });

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(QuestionBankValidationException.class)
                .hasMessageContaining("Loại câu hỏi");
    }

    private static MultipartFile build(String[][] grid) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Cau hoi");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "bank.xlsx", XLSX_MIME, out.toByteArray());
        }
    }
}
