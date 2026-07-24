package com.ulp.features.questionbank.imports;

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

/** Builds the .xlsx template used for department question bank imports. */
@Component
public class QuestionBankImportTemplate {

    private static final String SHEET_ROWS = "Cau hoi";
    private static final String SHEET_GUIDE = "Huong dan";
    private static final String[] HEADERS = {
            "Danh mục", "Loại câu hỏi", "Nội dung câu hỏi", "Giải thích",
            "Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D", "Đáp án E", "Đáp án F",
            "Đáp án đúng"
    };
    private static final String[][] SAMPLE_ROWS = {
            {"Giải tích 1", "MCQ", "Đạo hàm của x^2 là gì?", "Áp dụng quy tắc lũy thừa",
                    "2x", "x", "x^2", "2", "", "", "A"},
            {"Giải tích 1", "MR", "Chọn các hàm số liên tục trên R", "Có thể chọn nhiều đáp án",
                    "sin(x)", "|x|", "1/x", "x^2", "", "", "A,B,D"}
    };
    private static final String[] GUIDE_LINES = {
            "1. Dòng đầu tiên là tiêu đề, không được xoá hoặc đổi tên cột.",
            "2. Danh mục phải khớp chính xác với danh mục đang hoạt động trong bộ môn của bạn.",
            "3. Loại câu hỏi chỉ chấp nhận MCQ hoặc MR.",
            "4. Cần ít nhất hai đáp án không rỗng; có thể để trống đáp án E/F nếu không dùng.",
            "5. Cột 'Đáp án đúng' dùng chữ cái A-F, ngăn cách bằng dấu phẩy cho câu MR.",
            "6. MCQ phải có đúng một đáp án đúng; MR cần ít nhất một đáp án đúng."
    };
    private static final int COLUMN_WIDTH = 256 * 24;
    private static final int CONTENT_WIDTH = 256 * 42;

    /** Builds the workbook and serializes it to bytes for HTTP download. */
    public byte[] build() throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            buildRowsSheet(workbook, headerStyle);
            buildGuideSheet(workbook, headerStyle);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void buildRowsSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet(SHEET_ROWS);
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, i >= 2 && i <= 9 ? CONTENT_WIDTH : COLUMN_WIDTH);
        }
        for (int r = 0; r < SAMPLE_ROWS.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < SAMPLE_ROWS[r].length; c++) {
                row.createCell(c).setCellValue(SAMPLE_ROWS[r][c]);
            }
        }
    }

    private void buildGuideSheet(Workbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet(SHEET_GUIDE);
        Row title = sheet.createRow(0);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue("Quy tắc import");
        titleCell.setCellStyle(headerStyle);
        sheet.setColumnWidth(0, 256 * 100);
        for (int i = 0; i < GUIDE_LINES.length; i++) {
            sheet.createRow(i + 1).createCell(0).setCellValue(GUIDE_LINES[i]);
        }
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font bold = workbook.createFont();
        bold.setBold(true);
        style.setFont(bold);
        return style;
    }
}
