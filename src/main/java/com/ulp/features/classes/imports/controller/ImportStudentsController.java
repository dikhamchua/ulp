package com.ulp.features.classes.imports.controller;

import com.ulp.features.classes.imports.InvalidFileException;
import com.ulp.features.classes.imports.dto.ImportPayloads;
import com.ulp.features.classes.imports.dto.ImportResult;
import com.ulp.features.classes.imports.parser.ExcelTemplateBuilder;
import com.ulp.features.classes.imports.service.ImportStudentsService;
import com.ulp.features.classes.imports.session.ImportSession;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for the student-import feature (ULP-3.4).
 *
 * <p>Three endpoints are exposed under {@code /lecturer/classes/{classId}/import-students}:
 * <ul>
 *   <li>{@code POST /upload}            — multipart upload, returns the preview rows.</li>
 *   <li>{@code POST /{sessionId}/confirm} — confirms the import, returns the summary.</li>
 *   <li>{@code GET  /template}          — downloads a 2-row sample .xlsx file.</li>
 * </ul>
 *
 * <p>Authorization is enforced at two layers: {@link PreAuthorize} blocks
 * STUDENT and anonymous users at the class level, and the service layer
 * additionally rejects lecturers who do not own the requested class
 * ({@link AccessDeniedException} → HTTP 403).
 */
@RestController
@RequestMapping("/lecturer/classes/{classId}/import-students")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ImportStudentsController {

    private static final Logger log = LoggerFactory.getLogger(ImportStudentsController.class);

    // ── JSON envelope key for error payloads ─────────────────────
    private static final String JSON_KEY_ERROR = "error";

    // ── Template download metadata ───────────────────────────────
    private static final String XLSX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String TEMPLATE_FILENAME      = "mau-import-sinh-vien.xlsx";
    private static final String CONTENT_DISPOSITION_ATTACHMENT = "attachment";

    // ── Error messages (Vietnamese UI text) ──────────────────────
    private static final String MSG_PREVIEW_READ_FAILED = "Có lỗi không mong muốn khi đọc file.";
    private static final String MSG_CONFIRM_FAILED      = "Có lỗi không mong muốn khi import.";
    private static final String MSG_FORBIDDEN_IMPORT    = "Bạn không có quyền import vào lớp này.";

    private final ImportStudentsService importService;
    private final ExcelTemplateBuilder templateBuilder;

    public ImportStudentsController(ImportStudentsService importService,
                                    ExcelTemplateBuilder templateBuilder) {
        this.importService = importService;
        this.templateBuilder = templateBuilder;
    }

    /** Uploads an Excel file and returns the preview rows + a session id. */
    @PostMapping(value = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@PathVariable Long classId,
                                                      @RequestParam("file") MultipartFile file,
                                                      @AuthenticationPrincipal UlpUserDetails user) {
        try {
            ImportSession session = importService.previewUpload(
                    file, classId, user.getId(), user.getRole());
            return ResponseEntity.ok(ImportPayloads.preview(session));
        } catch (InvalidFileException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while previewing import for class {}", classId, ex);
            return internalError(MSG_PREVIEW_READ_FAILED);
        }
    }

    /**
     * Confirms a previously-previewed import. The request body is the JSON
     * {@code {"skipErrors": true|false}} flag.
     */
    @PostMapping(value = "/{sessionId}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Long classId,
                                                        @PathVariable UUID sessionId,
                                                        @RequestBody(required = false) ConfirmRequest body,
                                                        @AuthenticationPrincipal UlpUserDetails user) {
        try {
            boolean skip = body != null && body.skipErrors();
            ImportResult result = importService.confirmImport(
                    sessionId, classId, user.getId(), user.getRole(),
                    new ImportStudentsService.ImportOptions(skip));
            return ResponseEntity.ok(ImportPayloads.result(result));
        } catch (InvalidFileException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while confirming import for class {}", classId, ex);
            return internalError(MSG_CONFIRM_FAILED);
        }
    }

    /**
     * Streams a small .xlsx template generated on the fly by
     * {@link ExcelTemplateBuilder} so we never ship a binary asset alongside
     * the source.
     */
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable Long classId) {
        try {
            byte[] bytes = templateBuilder.build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(XLSX_MIME_TYPE));
            headers.setContentDispositionFormData(CONTENT_DISPOSITION_ATTACHMENT, TEMPLATE_FILENAME);
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException ex) {
            log.error("Failed to generate import template for class {}", classId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─────────────────────── helpers ────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(JSON_KEY_ERROR, message));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(JSON_KEY_ERROR, MSG_FORBIDDEN_IMPORT));
    }

    private static ResponseEntity<Map<String, Object>> internalError(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(JSON_KEY_ERROR, message));
    }

    /** JSON body for the confirm endpoint. */
    public record ConfirmRequest(boolean skipErrors) {}
}
