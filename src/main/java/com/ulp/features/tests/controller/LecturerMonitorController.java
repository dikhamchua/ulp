package com.ulp.features.tests.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.tests.dto.LecturerTestDtos.MonitorSnapshot;
import com.ulp.features.tests.dto.TestDtos.ReviewView;
import com.ulp.features.tests.service.ExamMonitorService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static com.ulp.common.IConstant.ATTR_REVIEW;
import static com.ulp.common.IConstant.BASE_LECTURER_TESTS;
import static com.ulp.common.IConstant.TAB_MONITOR;
import static com.ulp.common.IConstant.TAB_SUBMISSIONS;
import static com.ulp.common.IConstant.TAB_TESTS;
import static com.ulp.common.IConstant.VIEW_TEST_REVIEW;

/**
 * Lecturer-facing SSR controller for live monitoring, the submissions overview,
 * and per-attempt review of an owned exam. All actions are ownership-checked in
 * the service via {@code TestAccessResolver} (403 non-owner, 404 missing).
 */
@Controller
@RequestMapping(BASE_LECTURER_TESTS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerMonitorController {

    private final ExamMonitorService monitorService;
    private final ClassesService classesService;
    private final ClassDetailModelSupport classDetailSupport;

    public LecturerMonitorController(ExamMonitorService monitorService,
                                     ClassesService classesService,
                                     ClassDetailModelSupport classDetailSupport) {
        this.monitorService = monitorService;
        this.classesService = classesService;
        this.classDetailSupport = classDetailSupport;
    }

    /**
     * Legacy standalone monitor URL — now a tab on the exam detail page.
     * Redirect keeps old links / bookmarks working after the 4-tab merge.
     */
    @GetMapping("/{id}/monitor")
    public String monitor(@PathVariable Long id) {
        return "redirect:" + BASE_LECTURER_TESTS + "/" + id + "/edit?tab=" + TAB_MONITOR;
    }

    /** Monitor-data JSON endpoint polled by the monitor page (~30s). */
    @GetMapping(value = "/{id}/monitor/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public MonitorSnapshot monitorData(@PathVariable Long id,
                                       @AuthenticationPrincipal UlpUserDetails user) {
        return monitorService.snapshotFor(id, user.getId());
    }

    /**
     * Legacy standalone submissions URL — now a tab on the exam detail page.
     * Redirect (preserving the {@code q} search term) keeps old links working.
     */
    @GetMapping("/{id}/submissions")
    public String submissions(@PathVariable Long id,
                              @RequestParam(name = "q", required = false) String q) {
        String tail = (q == null || q.isBlank())
                ? "" : "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        return "redirect:" + BASE_LECTURER_TESTS + "/" + id + "/edit?tab=" + TAB_SUBMISSIONS + tail;
    }

    /**
     * Lecturer per-attempt review of an owned exam (reuses the review screen).
     * Wraps the review in the class-detail layout (left sidebar + main), like
     * the exam detail tabs — the "Bài test" nav item stays highlighted since
     * this screen is reached from that tab's submissions.
     */
    @GetMapping("/{id}/review/{attemptId}")
    public String review(@PathVariable Long id, @PathVariable Long attemptId,
                         @AuthenticationPrincipal UlpUserDetails user, Model model) {
        ReviewView review = monitorService.lecturerReview(id, attemptId, user.getId());
        model.addAttribute(ATTR_REVIEW, review);

        // Left sidebar chrome: the class this exam belongs to, mirroring the
        // exam detail layout. Absent for exams not tied to a class (classId
        // null) — the template then collapses to a single full-width column.
        if (review.classId() != null) {
            ClassEntity clazz = classesService.getViewable(review.classId(), user.getId(), user.getRole());
            classDetailSupport.populateDetail(model, clazz, TAB_TESTS, user.getId(), user.getRole());
        }
        return VIEW_TEST_REVIEW;
    }
}
