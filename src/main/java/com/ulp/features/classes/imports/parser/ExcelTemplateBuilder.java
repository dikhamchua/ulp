package com.ulp.features.classes.imports.parser;

import com.ulp.features.classes.imports.controller.ImportStudentsController;
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
 * Builds the .xlsx template streamed by
 * {@link ImportStudentsController#downloadTemplate(Long)}.
 *
 * <p>The file contains a single sheet ("Sinh viên") with a bold header row,
 * two sample data rows, and column widths sized for comfortable reading.
 *
 * <p>Extracted from the controller so the POI plumbing lives in one place and
 * keeps the controller method down to a handful of lines.
 */
@Component
public class ExcelTemplateBuilder {

    private static final String SHEET_NAME = "Sinh viên";

    private static final String[] HEADERS = {
            "Email", "MSSV", "Họ và tên", "Số điện thoại"
    };

    private static final String[][] SAMPLE_ROWS = {
            {"nguyen.van.a@example.com", "SV0001", "Nguyễn Văn A", "0901234567"},
            {"tran.thi.b@example.com",   "SV0002", "Trần Thị B",   "0907654321"},
    };

    /** Approximate cell width in POI units (256 = one character at default font). */
    private static final int COLUMN_WIDTH = 256 * 24;

    /**
     * Builds the .xlsx workbook and returns it as a byte array. Callers are
     * expected to forward the result through an HTTP response without further
     * processing.
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
