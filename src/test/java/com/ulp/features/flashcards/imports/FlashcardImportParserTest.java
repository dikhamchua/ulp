package com.ulp.features.flashcards.imports;

import com.ulp.features.flashcards.dto.FlashcardDtos.ImportedCardRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link FlashcardImportParser}: header skipping, blank-row
 * handling, format/size guards, and the row cap. Builds in-memory .xlsx files
 * with POI so no fixture files are needed.
 */
class FlashcardImportParserTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final FlashcardImportParser parser = new FlashcardImportParser();

    @Test
    void valid_two_column_file_skips_header_and_maps_sides() throws IOException {
        MultipartFile file = xlsx(new String[][]{
                {"Mặt trước", "Mặt sau"},   // header — skipped
                {"apple", "quả táo"},
                {"book", "quyển sách"},
        });

        List<ImportedCardRow> rows = parser.parse(file);

        assertEquals(2, rows.size());
        assertEquals("apple", rows.get(0).front());
        assertEquals("quả táo", rows.get(0).back());
        assertEquals("book", rows.get(1).front());
        assertEquals("quyển sách", rows.get(1).back());
    }

    @Test
    void wholly_blank_rows_are_skipped() throws IOException {
        MultipartFile file = xlsx(new String[][]{
                {"Mặt trước", "Mặt sau"},
                {"apple", "quả táo"},
                {"", ""},                    // blank both sides — dropped
                {"book", "quyển sách"},
        });

        List<ImportedCardRow> rows = parser.parse(file);

        assertEquals(2, rows.size());
    }

    @Test
    void row_with_blank_back_is_still_returned() throws IOException {
        MultipartFile file = xlsx(new String[][]{
                {"Mặt trước", "Mặt sau"},
                {"onlyfront", ""},
        });

        List<ImportedCardRow> rows = parser.parse(file);

        assertEquals(1, rows.size());
        assertEquals("onlyfront", rows.get(0).front());
        assertEquals("", rows.get(0).back());
    }

    @Test
    void non_excel_bytes_are_rejected() {
        MultipartFile file = new MockMultipartFile(
                "file", "cards.xlsx", XLSX_MIME, "this is not excel".getBytes());

        assertThrows(IllegalArgumentException.class, () -> parser.parse(file));
    }

    @Test
    void more_than_500_data_rows_are_rejected() throws IOException {
        String[][] data = new String[502][];
        data[0] = new String[]{"Mặt trước", "Mặt sau"};
        for (int i = 1; i <= 501; i++) {
            data[i] = new String[]{"f" + i, "b" + i};
        }

        MultipartFile file = xlsx(data);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(file));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Builds an .xlsx MockMultipartFile from a grid of cell strings. */
    private static MultipartFile xlsx(String[][] grid) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Thẻ");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "cards.xlsx", XLSX_MIME, out.toByteArray());
        }
    }
}
