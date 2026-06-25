package com.ulp.classes.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.auth.Role;
import com.ulp.classes.entity.ClassActivity;
import com.ulp.classes.entity.ClassEntity;
import com.ulp.classes.entity.Enrollment;
import com.ulp.classes.repository.EnrollmentRepository;
import com.ulp.classes.service.ClassActivityWriter;
import com.ulp.classes.service.ClassesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top-level service for the student-import feature (ULP-3.4).
 *
 * <p>{@link #previewUpload} validates the file and stages a pending
 * {@link ImportSession}. {@link #confirmImport} replays it, delegating per-row
 * persistence to {@link ImportRowProcessor} and writing a single
 * {@link ClassActivity} summary. Per-row failures are tolerated — they are
 * reported in {@link ImportResult} without aborting the surrounding transaction.
 *
 * <p>Authorization is delegated to {@link ClassesService#getEditable}: only the
 * lecturer who owns the class (plus HEAD / ADMIN) may import members.
 */
@Service
public class ImportStudentsService {

    private static final Logger log = LoggerFactory.getLogger(ImportStudentsService.class);
    private static final int SAVE_BATCH_SIZE = 50;

    /** Source code recorded on the enrollment row when it was created via Excel import. */
    public static final String JOINED_VIA_IMPORT = Enrollment.JoinedVia.IMPORT.name();

    private final ExcelParser excelParser;
    private final RowValidator rowValidator;
    private final ImportSessionStore sessionStore;
    private final ClassesService classesService;
    private final EnrollmentRepository enrollmentRepository;
    private final ImportRowProcessor rowProcessor;
    private final ClassActivityWriter activityWriter;
    private final ObjectMapper objectMapper;

    public ImportStudentsService(ExcelParser excelParser,
                                 RowValidator rowValidator,
                                 ImportSessionStore sessionStore,
                                 ClassesService classesService,
                                 EnrollmentRepository enrollmentRepository,
                                 ImportRowProcessor rowProcessor,
                                 ClassActivityWriter activityWriter,
                                 ObjectMapper objectMapper) {
        this.excelParser = excelParser;
        this.rowValidator = rowValidator;
        this.sessionStore = sessionStore;
        this.classesService = classesService;
        this.enrollmentRepository = enrollmentRepository;
        this.rowProcessor = rowProcessor;
        this.activityWriter = activityWriter;
        this.objectMapper = objectMapper;
    }

    /** Controller-supplied options that influence the confirm step. */
    public record ImportOptions(boolean skipErrors) {}

    /**
     * Validates the uploaded file, runs row-level validation, and persists a
     * pending {@link ImportSession} ready to be confirmed.
     *
     * @throws InvalidFileException on structural failures (size, format, headers …)
     * @throws org.springframework.security.access.AccessDeniedException if the lecturer does not own the class
     */
    @Transactional(readOnly = true)
    public ImportSession previewUpload(MultipartFile file, Long classId, Long lecturerId, Role role) {
        ClassEntity clazz = classesService.getEditable(classId, lecturerId, role);
        ExcelParser.ParsedFile parsed = excelParser.parse(file);
        List<ImportRow> rows = rowValidator.validate(parsed.rows(), clazz.getId());

        ImportSession session = new ImportSession(UUID.randomUUID(), clazz.getId(),
                lecturerId, Instant.now(), parsed.fileName(), rows);
        sessionStore.save(session);
        return session;
    }

    /**
     * Confirms a previously-previewed import. Rows are processed inside a
     * single transactional method, but each row is committed best-effort: a
     * row that fails to persist is logged as {@code failed} and the next row
     * continues.
     *
     * <p>When {@link ImportOptions#skipErrors()} is {@code false} and the
     * session contains any hard-error rows, the method returns immediately
     * without writing anything.
     */
    @Transactional
    public ImportResult confirmImport(UUID sessionId, Long classId, Long lecturerId,
                                      Role role, ImportOptions options) {
        ClassEntity clazz = classesService.getEditable(classId, lecturerId, role);
        ImportSession session = sessionStore.get(sessionId, lecturerId)
                .orElseThrow(() -> new InvalidFileException(
                        "Phiên import đã hết hạn hoặc không tồn tại. Vui lòng tải file lên lại."));

        if (!session.getClassId().equals(clazz.getId())) {
            throw new InvalidFileException("Phiên import không khớp với lớp hiện tại.");
        }

        long errorCount = session.errorCount();
        ImportOptions opts = options == null ? new ImportOptions(false) : options;
        if (errorCount > 0 && !opts.skipErrors()) {
            // Caller must confirm explicitly before importing past the errors.
            return new ImportResult(session.totalRows(), 0, 0, 0,
                    (int) errorCount, 0, session.getRows());
        }

        RowOutcome totals = processAllRows(session.getRows(), classId);
        writeActivity(classId, lecturerId, session, totals);
        sessionStore.delete(sessionId);

        return new ImportResult(session.totalRows(),
                totals.imported(), totals.reactivated(),
                totals.skippedDup(), totals.skippedErr(), totals.failed(),
                session.getRows());
    }

    /** Iterates every row, accumulates the totals, and flushes in batches of 50. */
    private RowOutcome processAllRows(List<ImportRow> rows, Long classId) {
        List<Enrollment> pending = new ArrayList<>(SAVE_BATCH_SIZE);
        RowOutcome totals = RowOutcome.ZERO;

        for (ImportRow row : rows) {
            if (row.getStatus() == null) continue;
            RowOutcome outcome;
            try {
                outcome = rowProcessor.process(row, classId, pending);
            } catch (RuntimeException ex) {
                // Log and continue — never let a single bad row roll the whole batch back.
                log.warn("Failed to persist import row {} for class {}: {}",
                        row.getRowNumber(), classId, ex.getMessage());
                row.mark(ImportRowStatus.USER_NOT_FOUND, "Lỗi lưu enrollment");
                outcome = RowOutcome.FAILED;
            }
            totals = totals.plus(outcome);
            if (pending.size() >= SAVE_BATCH_SIZE) flush(pending);
        }
        flush(pending);
        return totals;
    }

    private void flush(List<Enrollment> pending) {
        if (pending.isEmpty()) return;
        enrollmentRepository.saveAll(pending);
        pending.clear();
    }

    private void writeActivity(Long classId, Long lecturerId, ImportSession session,
                               RowOutcome totals) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName", session.getFileName());
        meta.put("totalRows", session.totalRows());
        meta.put("imported", totals.imported());
        meta.put("reactivated", totals.reactivated());
        meta.put("skippedDuplicate", totals.skippedDup());
        meta.put("skippedError", totals.skippedErr());
        meta.put("failed", totals.failed());

        String description = "Import danh sách sinh viên từ Excel — thêm "
                + (totals.imported() + totals.reactivated()) + " thành viên";
        activityWriter.write(classId, ClassActivity.TYPE_UPDATED, description, meta, lecturerId);

        // Defensive log line for debugging stuck imports — never bubble.
        try {
            log.debug("Import summary for class {}: {}", classId,
                    objectMapper.writeValueAsString(meta));
        } catch (JsonProcessingException ignored) {
            // ObjectMapper is shared with ClassActivityWriter; non-fatal.
        }
    }
}
