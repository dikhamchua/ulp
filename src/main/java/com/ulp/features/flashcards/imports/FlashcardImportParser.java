package com.ulp.features.flashcards.imports;

import com.ulp.features.flashcards.dto.FlashcardDtos.ImportedCardRow;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Parses an uploaded flashcard Excel file (.xlsx / .xls) into a list of
 * {@link ImportedCardRow} (column A = front, column B = back).
 *
 * <p>Self-contained and decoupled from the class-member importer: it mirrors the
 * same defence-in-depth checks (magic-byte format guard, 2 MB size cap, zip-bomb
 * ratio limits, 500-row cap) but knows nothing about student columns.
 *
 * <p>Sheet 0 only, row 0 treated as the header and skipped. Rows where BOTH
 * sides are blank are dropped; a row with at least one side is kept so the user
 * can fix the gap in the editor (the save flow validates non-blank both sides).
 * All failures raise {@link IllegalArgumentException} carrying a Vietnamese
 * message; the API controller maps it to HTTP 400.
 */
@Component
public class FlashcardImportParser {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_DATA_ROWS = 500;

    private static final byte[] MAGIC_ZIP = {(byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04};
    private static final byte[] MAGIC_OLE2 = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};

    static {
        // Zip-bomb defence (idempotent): reject xlsx with abnormal compression.
        // 0.005 = max 200x ratio; single entry capped at 50MB (>> 2MB upload cap).
        ZipSecureFile.setMinInflateRatio(0.005);
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024);
    }

    /**
     * Validates the file and returns its card rows (front = col A, back = col B).
     *
     * @throws IllegalArgumentException on empty/oversized/non-Excel/too-many-rows
     */
    public List<ImportedCardRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn một file Excel để tải lên.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File vượt quá kích thước tối đa 2 MB.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Không đọc được file đã tải lên.", ex);
        }
        if (!looksLikeExcel(bytes)) {
            throw new IllegalArgumentException("File không phải Excel hợp lệ.");
        }

        try (InputStream in = new BufferedInputStream(file.getInputStream());
             Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("File Excel không có sheet nào.");
            }
            return readSheet(workbook.getSheetAt(0));
        } catch (NotOfficeXmlFileException | IOException ex) {
            throw new IllegalArgumentException("File không phải Excel hợp lệ.", ex);
        }
    }

    /** Reads sheet 0: skip header row, map col 0 → front and col 1 → back. */
    private List<ImportedCardRow> readSheet(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.US);
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        List<ImportedCardRow> cards = new ArrayList<>();

        // Skip the header row (firstRow); data starts at the next row.
        for (int r = firstRow + 1; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String front = cellValue(formatter, row.getCell(0));
            String back = cellValue(formatter, row.getCell(1));
            // Drop wholly blank rows so trailing empties don't count toward the cap.
            if (front.isEmpty() && back.isEmpty()) continue;

            if (cards.size() >= MAX_DATA_ROWS) {
                throw new IllegalArgumentException(
                        "Vượt quá " + MAX_DATA_ROWS + " dòng cho phép trong một lần import.");
            }
            cards.add(new ImportedCardRow(front, back));
        }
        return cards;
    }

    private static String cellValue(DataFormatter formatter, Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /** Leading bytes match the ZIP/OOXML ({@code PK\x03\x04}) or legacy OLE2 magic. */
    private static boolean looksLikeExcel(byte[] bytes) {
        if (bytes.length >= MAGIC_OLE2.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_OLE2.length), MAGIC_OLE2)) {
            return true;
        }
        return bytes.length >= MAGIC_ZIP.length
                && Arrays.equals(Arrays.copyOf(bytes, MAGIC_ZIP.length), MAGIC_ZIP);
    }
}
