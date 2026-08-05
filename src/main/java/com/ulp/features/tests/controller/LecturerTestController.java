package com.ulp.features.tests.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.controller.support.ClassDetailModelSupport;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamForm;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamHeader;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.dto.LecturerTestDtos.SaveResult;
import com.ulp.features.tests.dto.TestDtos.PreviewView;
import com.ulp.features.tests.service.ExamMonitorService;
import com.ulp.features.tests.service.ExamQuestionBankPickerService;
import com.ulp.features.tests.service.LecturerExamQueryService;
import com.ulp.features.tests.service.LecturerExamService;
import com.ulp.features.storage.StorageNotConfiguredException;
import com.ulp.features.upload.ExamImageStorageService;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ulp.common.IConstant.ATTR_ACTIVE_DETAIL_TAB;
import static com.ulp.common.IConstant.ATTR_CLASS_ID;
import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_BANK_CHAPTERS;
import static com.ulp.common.IConstant.ATTR_EXAM_CLASS_OPTIONS;
import static com.ulp.common.IConstant.ATTR_EXAM_FILTER;
import static com.ulp.common.IConstant.ATTR_EXAM_FORM;
import static com.ulp.common.IConstant.ATTR_LED_CLASSES;
import static com.ulp.common.IConstant.ATTR_MODE;
import static com.ulp.common.IConstant.ATTR_MONITOR;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_PREVIEW;
import static com.ulp.common.IConstant.ATTR_SUBMISSIONS;
import static com.ulp.common.IConstant.ATTR_TEST;
import static com.ulp.common.IConstant.ATTR_TEST_ACTIVITIES_PAGE;
import static com.ulp.common.IConstant.BASE_LECTURER_TESTS;
import static com.ulp.common.IConstant.MODE_CREATE;
import static com.ulp.common.IConstant.MODE_EDIT;
import static com.ulp.common.IConstant.MSG_STORAGE_R2_NOT_CONFIGURED;
import static com.ulp.common.IConstant.MSG_STORAGE_UPLOAD_FAILED;
import static com.ulp.common.IConstant.PARAM_EXAM_CLASS;
import static com.ulp.common.IConstant.PARAM_EXAM_KEYWORD;
import static com.ulp.common.IConstant.PARAM_EXAM_SORT;
import static com.ulp.common.IConstant.PARAM_EXAM_STATUS;
import static com.ulp.common.IConstant.PARAM_EXAM_TYPE;
import static com.ulp.common.IConstant.TAB_HISTORY;
import static com.ulp.common.IConstant.TAB_INFO;
import static com.ulp.common.IConstant.TAB_MONITOR;
import static com.ulp.common.IConstant.TAB_SUBMISSIONS;
import static com.ulp.common.IConstant.VIEW_TEST_LECTURER_FORM;
import static com.ulp.common.IConstant.VIEW_TEST_LECTURER_LIST;
import static com.ulp.common.IConstant.VIEW_TEST_LECTURER_PREVIEW;
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
    private final LecturerExamQueryService examQueryService;
    private final ExamMonitorService monitorService;
    private final ClassesService classesService;
    private final ClassDetailModelSupport classDetailSupport;
    private final ExamImageStorageService examImageStorage;
    private final ExamQuestionBankPickerService questionBankPickerService;

    public LecturerTestController(LecturerExamService examService,
                                  LecturerExamQueryService examQueryService,
                                  ExamMonitorService monitorService,
                                  ClassesService classesService,
                                  ClassDetailModelSupport classDetailSupport,
                                  ExamImageStorageService examImageStorage,
                                  ExamQuestionBankPickerService questionBankPickerService) {
        this.examService = examService;
        this.examQueryService = examQueryService;
        this.monitorService = monitorService;
        this.classesService = classesService;
        this.classDetailSupport = classDetailSupport;
        this.examImageStorage = examImageStorage;
        this.questionBankPickerService = questionBankPickerService;
    }

    /**
     * Lists exams the lecturer owns across every class, narrowed by the optional
     * keyword / status / type / class filter and ordered by {@code sort} (SSR
     * numbered pager). Unknown filter values — including a {@code classId} the
     * lecturer does not lead — are sanitised to their defaults by
     * {@link ExamFilter#of}, so hand-typed URLs never 4xx and never widen scope.
     *
     * <p>A role with no owned exams (ADMIN leads no class) simply gets an empty
     * page: the query's ownership predicate is the data-scope gate, so the screen
     * renders its empty state instead of failing.
     */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = PARAM_EXAM_KEYWORD, required = false) String keyword,
                       @RequestParam(name = PARAM_EXAM_STATUS, required = false) String status,
                       @RequestParam(name = PARAM_EXAM_TYPE, required = false) String type,
                       // Bound as text, not Long: an unparsable id must sanitise to
                       // "all classes", not raise a bind error the advice turns into 500.
                       @RequestParam(name = PARAM_EXAM_CLASS, required = false) String classId,
                       @RequestParam(name = PARAM_EXAM_SORT, required = false) String sort,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        List<ClassOption> ledClasses = examQueryService.ledClasses(user.getId());
        ExamFilter filter =
                ExamFilter.ofRawClassId(keyword, status, type, sort, classId, ledClasses);
        Page<LecturerExamRow> exams = examQueryService.listOwned(user.getId(), page, filter);
        model.addAttribute(ATTR_EXAMS_PAGE, exams);
        model.addAttribute(ATTR_EXAM_FILTER, filter);
        model.addAttribute(ATTR_EXAM_CLASS_OPTIONS, ledClasses);
        model.addAttribute(ATTR_PAGER_PARAMS, pagerParamsOf(filter));
        return VIEW_TEST_LECTURER_LIST;
    }

    /**
     * Page-independent query params the pager must preserve. Built as a
     * LinkedHashMap because {@code Map.of} rejects null values and the class
     * dimension is absent whenever the filter spans every class.
     */
    private static Map<String, Object> pagerParamsOf(ExamFilter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(PARAM_EXAM_KEYWORD, filter.keyword());
        params.put(PARAM_EXAM_STATUS, filter.status());
        params.put(PARAM_EXAM_TYPE, filter.type());
        params.put(PARAM_EXAM_SORT, filter.sort());
        if (filter.classIdOrNull() != null) {
            params.put(PARAM_EXAM_CLASS, filter.classIdOrNull());
        }
        return params;
    }

    /**
     * Renders the create form with a blank question builder. When entered from a
     * class tests tab, {@code classId} preselects that class and the post-save
     * redirect points back at the class tests tab instead of the global list.
     * An id the lecturer does not lead is ignored (no prefill), so a hand-typed
     * value degrades to the plain create form rather than leaking a class.
     */
    @GetMapping("/new")
    public String newForm(@RequestParam(name = "classId", required = false) Long classId,
                          @AuthenticationPrincipal UlpUserDetails user, Model model) {
        List<ClassOption> ledClasses = examQueryService.ledClasses(user.getId());
        model.addAttribute(ATTR_EXAM_FORM, null);
        model.addAttribute(ATTR_LED_CLASSES, ledClasses);
        model.addAttribute(ATTR_CLASS_ID, preselectableClassId(classId, ledClasses));
        // Empty on purpose: chapters depend on the class, which the author has not
        // chosen yet. The client refetches them per selected class from
        // /lecturer/classes/{classId}/question-bank/chapters.
        model.addAttribute(ATTR_EXAM_BANK_CHAPTERS, java.util.List.of());
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        return VIEW_TEST_LECTURER_FORM;
    }

    /** Returns {@code classId} only when the lecturer leads it; otherwise null. */
    private static Long preselectableClassId(Long classId, List<ClassOption> ledClasses) {
        if (classId == null) return null;
        return ledClasses.stream().anyMatch(c -> classId.equals(c.id())) ? classId : null;
    }

    /**
     * Student-style preview of an owned exam. Read-only: no attempt is started
     * and answers cannot be submitted. Used from the edit toolbar "Xem trước".
     */
    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Long id,
                          @AuthenticationPrincipal UlpUserDetails user, Model model) {
        PreviewView preview = examService.previewAsStudent(id, user.getId());
        model.addAttribute(ATTR_PREVIEW, preview);
        return VIEW_TEST_LECTURER_PREVIEW;
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
        model.addAttribute(ATTR_LED_CLASSES, examQueryService.ledClasses(userId));
        model.addAttribute(ATTR_EXAM_BANK_CHAPTERS,
                questionBankPickerService.chaptersFor(userId, user.getRole(), id));
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        // Drives the post-save redirect back to this exam's class tests tab.
        model.addAttribute(ATTR_CLASS_ID, form.classId());
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

    /**
     * Uploads an image for embedding in question HTML (Quill toolbar).
     * Returns {@code { url: "/uploads/exams/..." }} inside the AjaxResult data.
     */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            String url = examImageStorage.store(file);
            return ResponseEntity.ok(AjaxResult.success(Map.of("url", url)));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (StorageNotConfiguredException ex) {
            return badRequest(MSG_STORAGE_R2_NOT_CONFIGURED);
        } catch (IOException ex) {
            log.error("Failed to upload exam image", ex);
            return badRequest(MSG_STORAGE_UPLOAD_FAILED);
        } catch (Exception ex) {
            log.error("Failed to upload exam image", ex);
            return internalError();
        }
    }
}
