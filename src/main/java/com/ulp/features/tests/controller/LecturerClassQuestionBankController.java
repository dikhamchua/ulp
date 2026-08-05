package com.ulp.features.tests.controller;

import com.ulp.features.tests.service.ExamQuestionBankPickerService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ulp.common.IConstant.URL_CLASSES_LIST;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * Read-only bank picker resolved from a class instead of a test, so exam
 * <em>create</em> mode can browse questions before the exam row exists. The
 * class subject supplies the same scope the test-scoped picker derives via
 * {@code test -> class}, so both modes list the identical union of the author's
 * private bank and the HEAD bank.
 *
 * <p><b>There is deliberately no insert endpoint here.</b> Create mode inserts
 * client-side into the question builder and persists through the normal exam
 * save; the server-side write path stays testId-only in
 * {@link LecturerTestQuestionBankController}. Do not "complete the CRUD" by
 * adding a POST — a classId write path would bypass the locked-shape check that
 * {@code LecturerExamService.insertFromBank} performs against student responses.
 */
@RestController
@RequestMapping(URL_CLASSES_LIST + "/{classId}/question-bank")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerClassQuestionBankController {

    private final ExamQuestionBankPickerService pickerService;

    public LecturerClassQuestionBankController(ExamQuestionBankPickerService pickerService) {
        this.pickerService = pickerService;
    }

    /** Chapters of the class subject; empty list when the class has no subject bound. */
    @GetMapping(value = "/chapters", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chapters(@PathVariable Long classId,
                                      @AuthenticationPrincipal UlpUserDetails user) {
        try {
            return ResponseEntity.ok(AjaxResult.success(
                    pickerService.chaptersForClass(user.getId(), user.getRole(), classId)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }

    /** Searches ACTIVE bank questions in the class scope, with scope info for empty states. */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@PathVariable Long classId,
                                    @RequestParam(name = "chapterId", required = false) Long chapterId,
                                    @RequestParam(name = "q", required = false) String q,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            return ResponseEntity.ok(AjaxResult.success(
                    pickerService.searchActiveForClass(user.getId(), user.getRole(), classId, chapterId, q)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }
}
