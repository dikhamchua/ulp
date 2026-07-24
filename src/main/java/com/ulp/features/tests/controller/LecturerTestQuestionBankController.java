package com.ulp.features.tests.controller;

import com.ulp.features.tests.dto.LecturerTestDtos.BankInsertRequest;
import com.ulp.features.tests.dto.LecturerTestDtos.BankInsertResult;
import com.ulp.features.tests.service.ExamQuestionBankPickerService;
import com.ulp.features.tests.service.LecturerExamService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.ulp.common.IConstant.BASE_LECTURER_TESTS;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * JSON endpoints that expose approved shared-bank questions for one test and
 * insert chosen ones as exam-owned snapshots. The bank itself is
 * department-scoped; the {@code testId} only resolves the working department.
 */
@RestController
@RequestMapping(BASE_LECTURER_TESTS + "/{testId}/question-bank")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerTestQuestionBankController {

    private final ExamQuestionBankPickerService pickerService;
    private final LecturerExamService examService;

    public LecturerTestQuestionBankController(ExamQuestionBankPickerService pickerService,
                                              LecturerExamService examService) {
        this.pickerService = pickerService;
        this.examService = examService;
    }

    /** Searches approved shared-bank questions the current actor may snapshot into this exam. */
    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> search(@PathVariable Long testId,
                                    @RequestParam(name = "categoryId", required = false) Long categoryId,
                                    @RequestParam(name = "q", required = false) String q,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            return ResponseEntity.ok(AjaxResult.success(
                    pickerService.searchApproved(user.getId(), user.getRole(), testId, categoryId, q)));
        } catch (AccessDeniedException ex) {
            return forbidden();
        }
    }

    /**
     * Inserts the chosen approved bank items into this test as exam-owned
     * snapshot rows. Snapshot copy only: later bank edits do not mutate the
     * inserted questions.
     */
    @PostMapping(value = "/insert", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> insert(@PathVariable Long testId,
                                    @RequestBody BankInsertRequest request,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            int inserted = examService.insertFromBank(
                    user.getId(), user.getRole(), testId,
                    request == null ? null : request.itemIds());
            return ResponseEntity.ok(AjaxResult.success(new BankInsertResult(inserted)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        }
    }
}
