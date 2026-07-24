package com.ulp.features.questionbank.controller;

import com.ulp.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import static com.ulp.common.IConstant.URL_HEAD_QUESTION_BANK_MANAGE;
import static com.ulp.common.IConstant.URL_LECTURER_QUESTION_BANK;

/**
 * Redirects the legacy test-scoped question-bank routes to the department-scoped
 * shared bank so old bookmarks do not surface 404/500 noise. The shared bank is
 * no longer attached to a test; the {@code testId} path segment is dropped.
 *
 * <p>NOTE: This controller became a thin compatibility shim after the shared
 * department question bank refactor and is a manual-delete candidate once no
 * legacy links remain.
 */
@Controller
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LegacyQuestionBankRedirectController {

    /** Sends legacy lecturer test-scoped links to the department contribution screen. */
    @GetMapping("/lecturer/tests/{testId}/question-bank")
    public String lecturerTestQuestionBankRedirect(@PathVariable Long testId) {
        return "redirect:" + URL_LECTURER_QUESTION_BANK;
    }

    /** Sends legacy HEAD test-scoped review links to the department management screen. */
    @GetMapping("/head/tests/{testId}/question-bank/review")
    public String headTestQuestionBankRedirect(@PathVariable Long testId) {
        return "redirect:" + URL_HEAD_QUESTION_BANK_MANAGE;
    }
}
