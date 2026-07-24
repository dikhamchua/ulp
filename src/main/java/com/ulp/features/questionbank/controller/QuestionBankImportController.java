package com.ulp.features.questionbank.controller;

import com.ulp.features.questionbank.dto.QuestionBankImportDtos.ConfirmRequest;
import com.ulp.features.questionbank.dto.QuestionBankImportDtos.ConfirmResult;
import com.ulp.features.questionbank.dto.QuestionBankImportDtos.Preview;
import com.ulp.features.questionbank.imports.QuestionBankImportSession;
import com.ulp.features.questionbank.imports.QuestionBankImportTemplate;
import com.ulp.features.questionbank.service.QuestionBankImportService;
import com.ulp.features.questionbank.service.QuestionBankValidationException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

import static com.ulp.common.IConstant.BASE_LECTURER_QUESTION_BANK;

/** REST endpoints for department-scoped Excel template download, preview, and confirm. */
@RestController
@RequestMapping(BASE_LECTURER_QUESTION_BANK + "/import")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class QuestionBankImportController {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankImportController.class);
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String TEMPLATE_FILENAME = "mau-import-cau-hoi-cong-tac.xlsx";
    private static final String JSON_KEY_ERROR = "error";
    private static final String MSG_FORBIDDEN = "Bạn không có quyền import câu hỏi cộng tác cho bộ môn này";
    private static final String MSG_UNEXPECTED = "Có lỗi không mong muốn khi xử lý file import";

    private final QuestionBankImportService importService;
    private final QuestionBankImportTemplate importTemplate;

    public QuestionBankImportController(QuestionBankImportService importService,
                                        QuestionBankImportTemplate importTemplate) {
        this.importService = importService;
        this.importTemplate = importTemplate;
    }

    @GetMapping(value = "/template", produces = XLSX_MIME)
    public ResponseEntity<byte[]> template() {
        try {
            byte[] bytes = importTemplate.build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
            headers.setContentDispositionFormData("attachment", TEMPLATE_FILENAME);
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException ex) {
            log.error("Failed to generate collaborative question import template", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal UlpUserDetails user) {
        try {
            QuestionBankImportSession session = importService.previewUpload(user.getId(), user.getRole(), file);
            Preview preview = session.toPreview();
            return ResponseEntity.ok(preview);
        } catch (QuestionBankValidationException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while previewing collaborative question import", ex);
            return internalError();
        }
    }

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirm(@RequestBody ConfirmRequest request,
                                     @AuthenticationPrincipal UlpUserDetails user) {
        try {
            ConfirmResult result = importService.confirm(user.getId(), user.getRole(), request.sessionId());
            return ResponseEntity.ok(result);
        } catch (QuestionBankValidationException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (RuntimeException ex) {
            log.error("Unexpected failure while confirming collaborative question import", ex);
            return internalError();
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(JSON_KEY_ERROR, message));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(JSON_KEY_ERROR, MSG_FORBIDDEN));
    }

    private static ResponseEntity<Map<String, Object>> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(JSON_KEY_ERROR, MSG_UNEXPECTED));
    }
}
