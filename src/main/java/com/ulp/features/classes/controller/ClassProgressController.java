package com.ulp.features.classes.controller;

import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.dto.ProgressDtos.ProgressPageView;
import com.ulp.features.classes.service.LecturerProgressService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.ulp.common.IConstant.ATTR_PROGRESS_PAGE;
import static com.ulp.common.IConstant.ATTR_PROGRESS_QUERY;
import static com.ulp.common.IConstant.ATTR_PROGRESS_SIZE;
import static com.ulp.common.IConstant.ATTR_PROGRESS_STATUS;
import static com.ulp.common.IConstant.ATTR_PROGRESS_SUMMARY;
import static com.ulp.common.IConstant.BASE_LECTURER;
import static com.ulp.common.IConstant.DEFAULT_PROGRESS_PAGE_SIZE;
import static com.ulp.common.IConstant.TAB_PROGRESS;
import static com.ulp.common.IConstant.VIEW_CLASS_DETAIL_PROGRESS;

/**
 * Renders the lecturer progress dashboard tab (lecturer-student-progress).
 *
 * <p>Kept separate from {@link ClassDetailController} so the aggregation-heavy
 * tab stays isolated. Class-level {@code @PreAuthorize} blocks STUDENT and
 * anonymous callers; ownership is enforced inside
 * {@link LecturerProgressService#getProgressPage} via
 * {@code classesService.getViewable}.
 *
 * <p>Exposed endpoint:
 * {@code GET /lecturer/classes/{id}/progress?status=&q=&page=&size=}.
 */
@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassProgressController {

    private final LecturerProgressService progressService;
    private final ClassDetailModelSupport detailSupport;

    public ClassProgressController(LecturerProgressService progressService,
                                   ClassDetailModelSupport detailSupport) {
        this.progressService = progressService;
        this.detailSupport = detailSupport;
    }

    /** Renders the progress tab: cohort summary + searchable/filterable/paginated table. */
    @GetMapping("/classes/{id}/progress")
    public String progress(@PathVariable Long id,
                           @RequestParam(name = "status", defaultValue = "all") String status,
                           @RequestParam(name = "q", defaultValue = "") String q,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           @RequestParam(name = "size",
                                   defaultValue = "" + DEFAULT_PROGRESS_PAGE_SIZE) int size,
                           @AuthenticationPrincipal UlpUserDetails user,
                           Model model) {
        ProgressPageView view = progressService.getProgressPage(
                id, user.getId(), user.getRole(), status, q, page, size);

        detailSupport.populateDetail(model, view.clazz(), TAB_PROGRESS,
                user.getId(), user.getRole());
        model.addAttribute(ATTR_PROGRESS_SUMMARY, view.summary());
        model.addAttribute(ATTR_PROGRESS_PAGE, view.rows());
        model.addAttribute(ATTR_PROGRESS_STATUS, status);
        model.addAttribute(ATTR_PROGRESS_QUERY, q);
        model.addAttribute(ATTR_PROGRESS_SIZE, size);
        return VIEW_CLASS_DETAIL_PROGRESS;
    }
}
