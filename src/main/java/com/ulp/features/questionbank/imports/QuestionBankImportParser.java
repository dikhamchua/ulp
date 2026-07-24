package com.ulp.features.questionbank.imports;

import com.ulp.features.questionbank.service.QuestionBankValidationException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses an uploaded Excel file into raw question-bank import rows. */
@Component
public class QuestionBankImportParser {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DATA_ROWS = 500;
    private static final byte[] MAGIC_ZIP = {(byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04};
    private static final byte[] MAGIC_OLE2 = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    private static final String COL_CATEGORY = "category";
    private static final String COL_TYPE = "questionType";
    private static final String COL_CONTENT = "content";
    private static final String COL_EXPLANATION = "explanation";
    private static final String COL_CORRECT = "correctAnswers";
    private static final Map<String, String> HEADER_ALIASES = buildAliases();

    static {
        ZipSecureFile.setMinInflateRatio(0.005);
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    public record ParsedFile(String fileName, List<RawRow> rows) {
    }

    public record RawRow(int rowNumber,
                         String categoryName,
                         String questionType,
                         String content,
                         String explanation,
                         List<String> optionValues,
                         String correctAnswers) {
    }

    /** Validates the uploaded file and extracts rows from the first sheet. */
    public ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new QuestionBankValidationException("Vui lòng chọn một file Excel để tải lên");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new QuestionBankValidationException("File vượt quá kích thước tối đa 2 MB");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new QuestionBankValidationException("Không đọc được file đã tải lên");
        }
        if (!looksLikeExcel(bytes)) {
            throw new QuestionBankValidationException("File không phải Excel hợp lệ");
        }

        try (InputStream in = new BufferedInputStream(file.getInputStream());
             Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new QuestionBankValidationException("File Excel không có sheet nào");
            }
            return readSheet(workbook.getSheetAt(0), file.getOriginalFilename());
        } catch (NotOfficeXmlFileException | IOException ex) {
            throw new QuestionBankValidationException("File không phải Excel hợp lệ");
        }
    }

    private ParsedFile readSheet(Sheet sheet, String fileName) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (headerRow == null) {
            throw new QuestionBankValidationException("Sheet đầu tiên không có dòng tiêu đề");
        }
        DataFormatter formatter = new DataFormatter(Locale.US);
        Map<Integer, String> headerByIndex = new HashMap<>();
        Map<Integer, String> optionLabels = new HashMap<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            String raw = cellValue(formatter, headerRow.getCell(c));
            String canonical = HEADER_ALIASES.get(normalize(raw));
            if (canonical == null && normalize(raw).matches("dap an [a-f]")) {
                canonical = "option:" + raw.trim().substring(raw.trim().length() - 1).toUpperCase(Locale.ROOT);
            }
            if (canonical != null) {
                headerByIndex.put(c, canonical);
                if (canonical.startsWith("option:")) {
                    optionLabels.put(c, canonical.substring("option:".length()));
                }
            }
        }
        if (!headerByIndex.containsValue(COL_CATEGORY)
                || !headerByIndex.containsValue(COL_TYPE)
                || !headerByIndex.containsValue(COL_CONTENT)
                || !headerByIndex.containsValue(COL_CORRECT)) {
            throw new QuestionBankValidationException(
                    "File mẫu phải có đủ cột Danh mục, Loại câu hỏi, Nội dung câu hỏi và Đáp án đúng");
        }

        List<RawRow> rows = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String category = "";
            String type = "";
            String content = "";
            String explanation = "";
            String correctAnswers = "";
            List<String> optionValues = new ArrayList<>();
            boolean anyValue = false;
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String canonical = headerByIndex.get(c);
                if (canonical == null) {
                    continue;
                }
                String value = cellValue(formatter, row.getCell(c));
                anyValue = anyValue || !value.isEmpty();
                switch (canonical) {
                    case COL_CATEGORY -> category = value;
                    case COL_TYPE -> type = value;
                    case COL_CONTENT -> content = value;
                    case COL_EXPLANATION -> explanation = value;
                    case COL_CORRECT -> correctAnswers = value;
                    default -> {
                        if (canonical.startsWith("option:")) {
                            optionValues.add(value);
                        }
                    }
                }
            }
            if (!anyValue) {
                continue;
            }
            if (rows.size() >= MAX_DATA_ROWS) {
                throw new QuestionBankValidationException(
                        "Vượt quá " + MAX_DATA_ROWS + " dòng cho phép trong một lần import");
            }
            while (optionValues.size() < optionLabels.size()) {
                optionValues.add("");
            }
            rows.add(new RawRow(r + 1, category, type, content, explanation, optionValues, correctAnswers));
        }
        return new ParsedFile(fileName, rows);
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd');
        decomposed = decomposed.replaceAll("[^a-z0-9]+", " ").trim();
        return decomposed.replaceAll("\\s+", " ");
    }

    private static String cellValue(DataFormatter formatter, Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static boolean looksLikeExcel(byte[] bytes) {
        if (bytes.length >= MAGIC_OLE2.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_OLE2.length), MAGIC_OLE2)) {
            return true;
        }
        return bytes.length >= MAGIC_ZIP.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_ZIP.length), MAGIC_ZIP);
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> map = new HashMap<>();
        map.put("danh muc", COL_CATEGORY);
        map.put("category", COL_CATEGORY);
        map.put("loai cau hoi", COL_TYPE);
        map.put("question type", COL_TYPE);
        map.put("noi dung cau hoi", COL_CONTENT);
        map.put("question content", COL_CONTENT);
        map.put("giai thich", COL_EXPLANATION);
        map.put("explanation", COL_EXPLANATION);
        map.put("dap an dung", COL_CORRECT);
        map.put("correct answers", COL_CORRECT);
        return Map.copyOf(map);
    }
}
