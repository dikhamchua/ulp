package com.ulp.classes.imports;

import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses an uploaded student-list Excel file (.xls / .xlsx) into a structured
 * {@link ParsedFile}.
 *
 * <p>Validates structural concerns at the file level: format (via magic bytes,
 * not just extension), size (≤2 MB), row count (≤500 data rows), sheet
 * existence, and presence of an identifier column. All failures raise
 * {@link InvalidFileException} carrying a Vietnamese message ready for display.
 *
 * <p>Header matching is forgiving: trims, lowercases, and strips Vietnamese
 * diacritics before comparing against a synonym map so cells like "Họ và tên",
 * "ho ten" and "FullName" all resolve to the same column.
 */
@Component
public class ExcelParser {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DATA_ROWS = 500;

    private static final byte[] MAGIC_ZIP = {(byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04};
    private static final byte[] MAGIC_OLE2 = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    // Canonical column keys recognized by the validator.
    static final String COL_EMAIL = "email";
    static final String COL_STUDENT_ID = "studentId";
    static final String COL_FULL_NAME = "fullName";
    static final String COL_PHONE = "phone";

    /**
     * Map of normalised header → canonical key. Comparison is performed in
     * lowercase with Vietnamese diacritics stripped and whitespace/underscores
     * collapsed.
     */
    private static final Map<String, String> HEADER_ALIASES = buildAliases();

    /** Identifies the spreadsheet and the parsed rows; produced by {@link #parse}. */
    public record ParsedFile(String fileName, List<String> headers, List<RawRow> rows) {}

    /** Raw row content before validation. Cells already trimmed; missing cells → empty strings. */
    public record RawRow(int rowNumber, String email, String studentId,
                         String fullName, String phone) {}

    /**
     * Validates the supplied file and produces a list of raw rows. The first
     * non-empty row is treated as the header row; data rows start from row 2.
     */
    public ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Vui lòng chọn một file Excel để tải lên.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new InvalidFileException("File vượt quá kích thước tối đa 2 MB.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new InvalidFileException("Không đọc được file đã tải lên.", ex);
        }

        if (!looksLikeExcel(bytes)) {
            throw new InvalidFileException("File không phải định dạng Excel (.xlsx hoặc .xls).");
        }

        try (InputStream in = new BufferedInputStream(file.getInputStream());
             Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new InvalidFileException("File Excel không có sheet nào.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            return readSheet(sheet, file.getOriginalFilename());
        } catch (NotOfficeXmlFileException ex) {
            throw new InvalidFileException("File không phải định dạng Excel (.xlsx hoặc .xls).", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new InvalidFileException("Không đọc được nội dung file Excel.", ex);
        }
    }

    private ParsedFile readSheet(Sheet sheet, String fileName) {
        int firstRow = sheet.getFirstRowNum();
        Row headerRow = sheet.getRow(firstRow);
        if (headerRow == null) {
            throw new InvalidFileException("Sheet đầu tiên không có dòng tiêu đề.");
        }

        DataFormatter formatter = new DataFormatter(Locale.US);
        Map<Integer, String> headerByIndex = new HashMap<>();
        List<String> headersOriginal = new ArrayList<>();
        Set<String> seenColumns = new HashSet<>();

        short lastCell = headerRow.getLastCellNum();
        for (int c = 0; c < lastCell; c++) {
            Cell cell = headerRow.getCell(c);
            String raw = cell == null ? "" : formatter.formatCellValue(cell).trim();
            headersOriginal.add(raw);
            String canonical = HEADER_ALIASES.get(normalize(raw));
            if (canonical != null && !seenColumns.contains(canonical)) {
                headerByIndex.put(c, canonical);
                seenColumns.add(canonical);
            }
        }

        if (!seenColumns.contains(COL_EMAIL) && !seenColumns.contains(COL_STUDENT_ID)) {
            throw new InvalidFileException("File phải có cột Email hoặc MSSV ở dòng tiêu đề.");
        }

        int lastRow = sheet.getLastRowNum();
        List<RawRow> rows = new ArrayList<>();
        int dataRowsParsed = 0;

        for (int r = firstRow + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String email = "";
            String studentId = "";
            String fullName = "";
            String phone = "";
            boolean anyValue = false;

            for (Map.Entry<Integer, String> entry : headerByIndex.entrySet()) {
                Cell cell = row.getCell(entry.getKey());
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                if (!value.isEmpty()) anyValue = true;
                switch (entry.getValue()) {
                    case COL_EMAIL -> email = value;
                    case COL_STUDENT_ID -> studentId = value;
                    case COL_FULL_NAME -> fullName = value;
                    case COL_PHONE -> phone = value;
                    default -> { /* unknown canonical key — ignore */ }
                }
            }

            if (!anyValue) {
                continue; // skip wholly blank rows so trailing empty rows do not count toward the cap
            }

            dataRowsParsed++;
            if (dataRowsParsed > MAX_DATA_ROWS) {
                throw new InvalidFileException(
                        "Vượt quá " + MAX_DATA_ROWS + " dòng cho phép trong một lần import.");
            }

            // rowNumber is 1-based, matching what Excel shows to the lecturer.
            rows.add(new RawRow(r + 1, email, studentId, fullName, phone));
        }

        return new ParsedFile(fileName, headersOriginal, rows);
    }

    /**
     * Returns {@code true} when the leading bytes match either the ZIP/OOXML
     * magic ({@code PK\x03\x04}) or the legacy OLE2 magic
     * ({@code \xD0\xCF\x11\xE0\xA1\xB1\x1A\xE1}).
     */
    private static boolean looksLikeExcel(byte[] bytes) {
        if (bytes.length >= MAGIC_OLE2.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_OLE2.length), MAGIC_OLE2)) {
            return true;
        }
        return bytes.length >= MAGIC_ZIP.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_ZIP.length), MAGIC_ZIP);
    }

    /** Lowercases, strips Vietnamese diacritics, removes punctuation, collapses whitespace. */
    static String normalize(String raw) {
        if (raw == null) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        // Replace đ/Đ which NFD does not decompose.
        decomposed = decomposed.replace('đ', 'd');
        // Replace non-alphanumeric with a single space, then collapse whitespace.
        decomposed = decomposed.replaceAll("[^a-z0-9]+", " ").trim();
        return decomposed.replaceAll("\\s+", " ");
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new HashMap<>();

        // Email column synonyms.
        for (String alias : List.of("email", "e mail", "mail", "dia chi email", "dia chi mail")) {
            map.put(alias, COL_EMAIL);
        }

        // Student ID column synonyms.
        for (String alias : List.of("mssv", "ma sinh vien", "ma sv", "msv",
                "student id", "studentid", "student code", "ma so sinh vien")) {
            map.put(alias, COL_STUDENT_ID);
        }

        // Full name column synonyms.
        for (String alias : List.of("ho ten", "ho va ten", "ten", "fullname", "full name",
                "name", "hoten", "ten day du")) {
            map.put(alias, COL_FULL_NAME);
        }

        // Phone column synonyms.
        for (String alias : List.of("so dien thoai", "sdt", "phone", "phone number",
                "dien thoai", "mobile")) {
            map.put(alias, COL_PHONE);
        }

        return Map.copyOf(map);
    }
}
