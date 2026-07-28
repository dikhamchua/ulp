package com.ulp.features.ai.questiongen;

import com.ulp.features.ai.client.AiClientException;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.ConfirmRequest;
import com.ulp.features.ai.questiongen.AiQuestionGenDtos.GenerateRequest;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static com.ulp.common.IConstant.BASE_LECTURER_TESTS;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * JSON endpoints backing the "Sinh câu hỏi bằng AI" panel on the exam edit screen.
 *
 * <p>Two steps rather than one: generate returns an unsaved preview, confirm persists only
 * what the lecturer ticked. The exam is never mutated by the model alone.
 */
@RestController
@RequestMapping(BASE_LECTURER_TESTS + "/{testId}/ai-questions")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class AiQuestionGenerationController {

    private static final String MSG_NO_MATERIAL =
            "Vui lòng tải lên file PDF/DOCX hoặc dán nội dung tài liệu";

    private final AiQuestionGenerationService generationService;

    public AiQuestionGenerationController(AiQuestionGenerationService generationService) {
        this.generationService = generationService;
    }

    /**
     * Generates a preview of questions from an uploaded document or pasted text.
     *
     * <p>Multipart rather than JSON because the file and the parameters arrive together;
     * both {@code file} and {@code text} are optional individually, but one is required.
     *
     * @param testId     the exam being edited
     * @param file       an uploaded PDF or DOCX, optional when text is pasted
     * @param text       pasted material, optional when a file is uploaded
     * @param count      how many questions to generate
     * @param type       {@code MCQ} or {@code MR}
     * @param difficulty easy/medium/hard hint
     * @param user       the authenticated lecturer
     * @return the preview payload, or a Vietnamese error message
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generate(@PathVariable Long testId,
                                      @RequestParam(name = "file", required = false) MultipartFile file,
                                      @RequestParam(name = "text", required = false) String text,
                                      @RequestParam(name = "count", defaultValue = "5") int count,
                                      @RequestParam(name = "type", required = false) String type,
                                      @RequestParam(name = "difficulty", required = false) String difficulty,
                                      @AuthenticationPrincipal UlpUserDetails user) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (!hasFile && !hasText) {
            return badRequest(MSG_NO_MATERIAL);
        }
        try {
            return ResponseEntity.ok(AjaxResult.success(generationService.generate(
                    user.getId(), testId, hasFile ? file : null, text,
                    new GenerateRequest(count, type, difficulty))));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AiClientException ex) {
            // Provider outage or misconfiguration: surfaced to the lecturer as a retryable
            // failure, not a 500 — the exam itself is untouched.
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }

    /**
     * Appends the ticked drafts of a preview session to the exam.
     *
     * @param testId  the exam being edited
     * @param request the session id plus the zero-based indexes to keep
     * @param user    the authenticated lecturer
     * @return how many questions were inserted, or a Vietnamese error message
     */
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirm(@PathVariable Long testId,
                                     @RequestBody(required = false) ConfirmRequest request,
                                     @AuthenticationPrincipal UlpUserDetails user) {
        try {
            return ResponseEntity.ok(AjaxResult.success(
                    generationService.confirm(user.getId(), testId, request)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }
}
