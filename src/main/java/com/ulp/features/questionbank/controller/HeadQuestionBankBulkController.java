package com.ulp.features.questionbank.controller;

import com.ulp.features.questionbank.controller.HeadQuestionBankController.ReviewFilters;
import com.ulp.features.questionbank.service.QuestionBankReviewService;
import com.ulp.features.questionbank.service.QuestionBankReviewService.BulkResult;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

import static com.ulp.common.IConstant.BASE_HEAD_QUESTION_BANK;
import static com.ulp.common.IConstant.MSG_QB_BULK_APPROVED_PREFIX;
import static com.ulp.common.IConstant.MSG_QB_BULK_ARCHIVED_PREFIX;
import static com.ulp.common.IConstant.MSG_QB_BULK_EMPTY;
import static com.ulp.common.IConstant.MSG_QB_BULK_REJECTED_PREFIX;
import static com.ulp.common.IConstant.MSG_QB_BULK_SKIPPED_PREFIX;
import static com.ulp.common.IConstant.MSG_QB_BULK_SKIPPED_SUFFIX;
import static com.ulp.common.IConstant.MSG_QB_BULK_SUFFIX_ITEMS;
import static com.ulp.common.IConstant.MSG_QB_BULK_UNARCHIVED_PREFIX;
import static com.ulp.common.IConstant.PARAM_QB_ITEM_IDS;

/**
 * HEAD bulk review actions on the category detail screen: approve/reject/archive
 * many selected questions at once. Split from {@link HeadQuestionBankController}
 * to keep each controller focused; both map under the same base path.
 */
@Controller
@RequestMapping(BASE_HEAD_QUESTION_BANK)
@PreAuthorize("hasRole('" + Roles.HEAD + "')")
public class HeadQuestionBankBulkController {

    private final QuestionBankReviewService reviewService;

    public HeadQuestionBankBulkController(QuestionBankReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/categories/{categoryId}/bulk/approve")
    public String bulkApprove(@PathVariable Long categoryId,
                              @RequestParam(name = PARAM_QB_ITEM_IDS, required = false) List<Long> itemIds,
                              ReviewFilters filters,
                              @AuthenticationPrincipal UlpUserDetails user,
                              RedirectAttributes ra) {
        if (isEmpty(itemIds)) {
            ra.addFlashAttribute("flashError", MSG_QB_BULK_EMPTY);
            return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
        }
        BulkResult result = reviewService.approveAll(user.getId(), itemIds);
        ra.addFlashAttribute("flashSuccess", bulkMessage(MSG_QB_BULK_APPROVED_PREFIX, result));
        return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
    }

    @PostMapping("/categories/{categoryId}/bulk/reject")
    public String bulkReject(@PathVariable Long categoryId,
                             @RequestParam(name = PARAM_QB_ITEM_IDS, required = false) List<Long> itemIds,
                             @RequestParam(name = "note", required = false) String note,
                             ReviewFilters filters,
                             @AuthenticationPrincipal UlpUserDetails user,
                             RedirectAttributes ra) {
        if (isEmpty(itemIds)) {
            ra.addFlashAttribute("flashError", MSG_QB_BULK_EMPTY);
            return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
        }
        BulkResult result = reviewService.rejectAll(user.getId(), itemIds, note);
        ra.addFlashAttribute("flashSuccess", bulkMessage(MSG_QB_BULK_REJECTED_PREFIX, result));
        return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
    }

    @PostMapping("/categories/{categoryId}/bulk/archive")
    public String bulkArchive(@PathVariable Long categoryId,
                              @RequestParam(name = PARAM_QB_ITEM_IDS, required = false) List<Long> itemIds,
                              @RequestParam(name = "note", required = false) String note,
                              ReviewFilters filters,
                              @AuthenticationPrincipal UlpUserDetails user,
                              RedirectAttributes ra) {
        if (isEmpty(itemIds)) {
            ra.addFlashAttribute("flashError", MSG_QB_BULK_EMPTY);
            return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
        }
        BulkResult result = reviewService.archiveAll(user.getId(), itemIds, note);
        ra.addFlashAttribute("flashSuccess", bulkMessage(MSG_QB_BULK_ARCHIVED_PREFIX, result));
        return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
    }

    @PostMapping("/categories/{categoryId}/bulk/unarchive")
    public String bulkUnarchive(@PathVariable Long categoryId,
                                @RequestParam(name = PARAM_QB_ITEM_IDS, required = false) List<Long> itemIds,
                                ReviewFilters filters,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        if (isEmpty(itemIds)) {
            ra.addFlashAttribute("flashError", MSG_QB_BULK_EMPTY);
            return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
        }
        BulkResult result = reviewService.unarchiveAll(user.getId(), itemIds);
        ra.addFlashAttribute("flashSuccess", bulkMessage(MSG_QB_BULK_UNARCHIVED_PREFIX, result));
        return HeadQuestionBankController.redirectDetail(categoryId, filters, ra);
    }

    /** "Đã duyệt X câu[, bỏ qua Y câu không hợp lệ]" built from a bulk outcome. */
    private static String bulkMessage(String prefix, BulkResult result) {
        String message = prefix + result.succeeded() + MSG_QB_BULK_SUFFIX_ITEMS;
        if (result.skipped() > 0) {
            message += MSG_QB_BULK_SKIPPED_PREFIX + result.skipped() + MSG_QB_BULK_SKIPPED_SUFFIX;
        }
        return message;
    }

    private static boolean isEmpty(List<Long> itemIds) {
        return itemIds == null || itemIds.isEmpty();
    }
}
