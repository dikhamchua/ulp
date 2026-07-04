package com.ulp.features.tests.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamHeader;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.dto.LecturerTestDtos.SaveResult;
import com.ulp.features.tests.service.ExamMonitorService;
import com.ulp.features.tests.service.LecturerExamService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.ATTR_ACTIVE_DETAIL_TAB;
import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_FORM;
import static com.ulp.common.IConstant.ATTR_LED_CLASSES;
import static com.ulp.common.IConstant.ATTR_MODE;
import static com.ulp.common.IConstant.ATTR_MONITOR;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_SUBMISSIONS;
import static com.ulp.common.IConstant.ATTR_TEST;
import static com.ulp.common.IConstant.ATTR_TEST_ACTIVITIES_PAGE;
import static com.ulp.common.IConstant.BASE_LECTURER_TESTS;
import static com.ulp.common.IConstant.MODE_CREATE;
import static com.ulp.common.IConstant.MODE_EDIT;
import static com.ulp.common.IConstant.TAB_HISTORY;
import static com.ulp.common.IConstant.TAB_INFO;
import static com.ulp.common.IConstant.TAB_MONITOR;
import static com.ulp.common.IConstant.TAB_SUBMISSIONS;
import static com.ulp.common.IConstant.VIEW_TEST_LECTURER_FORM;
import static com.ulp.common.IConstant.VIEW_TEST_LECTURER_LIST;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * Lecturer-facing SSR controller for exam authoring under {@code /lecturer/tests}:
 * list owned exams, render the create/edit form (question builder), and a JSON
 * save endpoint. Ownership is enforced in the service via {@code TestAccessResolver}.
 */
@Controller
@RequestMapping(BASE_LECTURER_TESTS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LecturerTestController {

    private static final Logger log = LoggerFactory.getLogger(LecturerTestController.class);

    /** Page size for the "Lịch sử" tab (mirrors the admin user-detail history tab). */
    private static final int HISTORY_PAGE_SIZE = 20;

    /** Whitelist of valid {@code tab} values; anything else falls back to {@code info}. */
    private static final Set<String> VALID_TABS =
            Set.of(TAB_INFO, TAB_MONITOR, TAB_SUBMISSIONS, TAB_HISTORY);

    private final LecturerExamService examService;
    private final ExamMonitorService monitorService;
    private final ClassesService classesService;
    private final ClassDetailModelSupport classDetailSupport;

    public LecturerTestController(LecturerExamService examService,
                                  ExamMonitorService monitorService,
                                  ClassesService classesService,
                                  ClassDetailModelSupport classDetailSupport) {
        this.examService = examService;
        this.monitorService = monitorService;
        this.classesService = classesService;
        this.classDetailSupport = classDetailSupport;
    }

    /** Lists exams the lecturer owns (SSR numbered pager). */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        Page<LecturerExamRow> exams = examService.listOwned(user.getId(), page);
        model.addAttribute(ATTR_EXAMS_PAGE, exams);
        return VIEW_TEST_LECTURER_LIST;
    }

    /** Renders the create form with a blank question builder. */
    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        model.addAttribute(ATTR_EXAM_FORM, null);
        model.addAttribute(ATTR_LED_CLASSES, examService.ledClasses(user.getId()));
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        return VIEW_TEST_LECTURER_FORM;
    }

    /**
     * Renders the exam detail page — a single screen with four tabs
     * (thông tin / theo dõi / bài nộp / lịch sử), routed via {@code ?tab=}.
     * Mirrors the admin user-detail pattern: the info tab hosts the question
     * builder form; the other tabs are loaded lazily per request. Invalid
     * {@code tab} values fall back to {@code info}.
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @RequestParam(name = "tab", required = false, defaultValue = TAB_INFO) String tab,
                           @RequestParam(name = "q", required = false) String q,
                           @RequestParam(name = "page", defaultValue = "0") int page,
                           @AuthenticationPrincipal UlpUserDetails user, Model model) {
        Long userId = user.getId();
        String activeTab = VALID_TABS.contains(tab) ? tab : TAB_INFO;

        // Header + info-form are always needed (page chrome + tab #1 content).
        ExamForm form = examService.getForEdit(id, userId);
        model.addAttribute(ATTR_EXAM_FORM, form);
        model.addAttribute(ATTR_LED_CLASSES, examService.ledClasses(userId));
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_TEST, monitorService.header(id, userId));
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeTab);

        // Left sidebar chrome: the class this exam belongs to (share box + class
        // nav), mirroring the class-detail layout. No class nav item maps to the
        // tests screen, so no sidebar entry is highlighted (activeTab sentinel).
        if (form.classId() != null) {
            ClassEntity clazz = classesService.getViewable(form.classId(), userId, user.getRole());
            classDetailSupport.populateDetail(model, clazz, TAB_INFO, userId, user.getRole());
        }

        // Lazy per-tab data — only query what the active tab renders.
        if (TAB_MONITOR.equals(activeTab)) {
            model.addAttribute(ATTR_MONITOR, monitorService.snapshotFor(id, userId));
        } else if (TAB_SUBMISSIONS.equals(activeTab)) {
            model.addAttribute(ATTR_SUBMISSIONS, monitorService.submissionsFor(id, userId, q, page));
            model.addAttribute(ATTR_PAGER_PARAMS, Map.of("tab", TAB_SUBMISSIONS, "q", q == null ? "" : q));
        } else if (TAB_HISTORY.equals(activeTab)) {
            model.addAttribute(ATTR_TEST_ACTIVITIES_PAGE,
                    monitorService.historyFor(id, userId, PageRequest.of(Math.max(0, page), HISTORY_PAGE_SIZE)));
        }
        return VIEW_TEST_LECTURER_FORM;
    }

    /**
     * Creates or updates an exam from the JSON question-builder payload; field
     * validation + ownership map onto the shared {@link AjaxResult} envelope.
     */
    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> save(@RequestBody ExamForm form,
                                  @AuthenticationPrincipal UlpUserDetails user) {
        try {
            Long id = examService.save(user.getId(), form);
            return ResponseEntity.ok(AjaxResult.success(new SaveResult(id)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to save exam", ex);
            return internalError();
        }
    }
}
