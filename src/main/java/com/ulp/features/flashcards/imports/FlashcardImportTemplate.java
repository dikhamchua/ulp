package com.ulp.features.flashcards.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Builds the .xlsx template offered on the deck editor's Import Excel control:
 * a single sheet with a bold header row ("Mặt trước" | "Mặt sau") and two
 * example rows so the user sees the expected two-column layout.
 */
@Component
public class FlashcardImportTemplate {

    private static final String SHEET_NAME = "Thẻ";
    private static final String[] HEADERS = {"Mặt trước", "Mặt sau"};
    private static final String[][] SAMPLE_ROWS = {
            {"apple", "quả táo"},
            {"book", "quyển sách"},
    };

    /** Approximate cell width in POI units (256 = one character at default font). */
    private static final int COLUMN_WIDTH = 256 * 28;

    /**
     * Builds the .xlsx workbook and returns it as a byte array.
     *
     * @throws IOException if the workbook fails to serialize
     */
    public byte[] build() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);

            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(headerStyle);
            }

            for (int r = 0; r < SAMPLE_ROWS.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < SAMPLE_ROWS[r].length; c++) {
                    row.createCell(c).setCellValue(SAMPLE_ROWS[r][c]);
                }
            }

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, COLUMN_WIDTH);
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }
}
