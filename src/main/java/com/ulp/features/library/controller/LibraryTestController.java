package com.ulp.features.library.controller;

import com.ulp.features.library.controller.support.LibraryBadgeCounts;
import com.ulp.features.library.dto.LibraryDtos.LibraryLessonsPageView;
import com.ulp.features.library.service.LessonTemplateService;
import com.ulp.features.tests.dto.LecturerTestDtos.ClassOption;
import com.ulp.features.tests.dto.LecturerTestDtos.ExamFilter;
import com.ulp.features.tests.dto.LecturerTestDtos.LecturerExamRow;
import com.ulp.features.tests.service.ExamCloneService;
import com.ulp.features.tests.service.LecturerExamQueryService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_EXAMS_PAGE;
import static com.ulp.common.IConstant.ATTR_EXAM_CLASS_OPTIONS;
import static com.ulp.common.IConstant.ATTR_EXAM_FILTER;
import static com.ulp.common.IConstant.ATTR_LIBRARY_KIND;
import static com.ulp.common.IConstant.ATTR_LIBRARY_QUERY;
import static com.ulp.common.IConstant.ATTR_LIBRARY_SIZE;
import static com.ulp.common.IConstant.ATTR_LIBRARY_TAB;
import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.DEFAULT_EXAM_PAGE_SIZE;
import static com.ulp.common.IConstant.LIBRARY_TAB_TESTS;
import static com.ulp.common.IConstant.MSG_EXAM_CLONE_OK;
import static com.ulp.common.IConstant.MSG_EXAM_DELETED;
import static com.ulp.common.IConstant.PARAM_EXAM_CLASS;
import static com.ulp.common.IConstant.PARAM_EXAM_KEYWORD;
import static com.ulp.common.IConstant.PARAM_EXAM_SORT;
import static com.ulp.common.IConstant.PARAM_EXAM_STATUS;
import static com.ulp.common.IConstant.PARAM_EXAM_TYPE;
import static com.ulp.common.IConstant.PARAM_EXAM_TARGET_CLASS;
import static com.ulp.common.IConstant.PATH_LIBRARY_TEST_CLONE;
import static com.ulp.common.IConstant.PATH_LIBRARY_TEST_DELETE;
import static com.ulp.common.IConstant.URL_LIBRARY_TESTS;
import static com.ulp.common.IConstant.VIEW_LIBRARY;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * SSR + JSON for the Library "Bài test" rail: the lecturer's owned exams with
 * the same filter/column fidelity as {@code /lecturer/tests}, plus the two
 * capabilities that screen does not have — clone into another led class and
 * soft delete.
 *
 * <p>Authorization is two-layer. {@code @PreAuthorize} (layer 1) lets LECTURER,
 * HEAD and ADMIN in. Layer 2 differs per path: the listing relies on the
 * ownership predicate inside {@code LecturerExamQueryService.listOwned}, which
 * returns an empty page rather than throwing, so an ADMIN (who leads no class)
 * gets the empty state at HTTP 200. The write paths reject explicitly through
 * {@code ExamCloneService}, which resolves ownership and the target class.
 */
@Controller
@RequestMapping(URL_LIBRARY_TESTS)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LibraryTestController {

    private static final Logger log = LoggerFactory.getLogger(LibraryTestController.class);

    private final LecturerExamQueryService examQueryService;
    private final ExamCloneService examCloneService;
    private final LessonTemplateService templateService;
    private final LibraryBadgeCounts badgeCounts;

    public LibraryTestController(LecturerExamQueryService examQueryService,
                                 ExamCloneService examCloneService,
                                 LessonTemplateService templateService,
                                 LibraryBadgeCounts badgeCounts) {
        this.examQueryService = examQueryService;
        this.examCloneService = examCloneService;
        this.templateService = templateService;
        this.badgeCounts = badgeCounts;
    }

    /**
     * Renders the rail: one page of exams the lecturer owns, narrowed by the
     * five filters, plus every sidebar badge count.
     *
     * <p>Listing goes through {@code listOwned} rather than the repository:
     * {@code TestRepository.searchOwnedByLecturer} requires a non-empty
     * {@code classIds}, and only the service supplies the {@code -1L} sentinel
     * that keeps the JPQL {@code IN} clause valid for a lecturer with no class.
     */
    @GetMapping
    public String page(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = PARAM_EXAM_KEYWORD, required = false) String keyword,
                       @RequestParam(name = PARAM_EXAM_STATUS, required = false) String status,
                       @RequestParam(name = PARAM_EXAM_TYPE, required = false) String type,
                       // Bound as text, not Long: an unparsable id must sanitise to
                       // "all classes", not raise a bind error the advice turns into 500.
                       @RequestParam(name = PARAM_EXAM_CLASS, required = false) String classId,
                       @RequestParam(name = PARAM_EXAM_SORT, required = false) String sort,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        Long userId = user.getId();
        List<ClassOption> ledClasses = examQueryService.ledClasses(userId);
        ExamFilter filter =
                ExamFilter.ofRawClassId(keyword, status, type, sort, classId, ledClasses);
        Page<LecturerExamRow> exams = examQueryService.listOwned(userId, page, filter);

        model.addAttribute(ATTR_EXAMS_PAGE, exams);
        model.addAttribute(ATTR_EXAM_FILTER, filter);
        model.addAttribute(ATTR_EXAM_CLASS_OPTIONS, ledClasses);
        model.addAttribute(ATTR_PAGER_PARAMS, pagerParamsOf(filter));

        // Library shell chrome: the rail branches on libraryTab, and the search
        // box / kind links read query+size even on this tab.
        model.addAttribute(ATTR_LIBRARY_TAB, LIBRARY_TAB_TESTS);
        model.addAttribute(ATTR_LIBRARY_KIND, "");
        model.addAttribute(ATTR_LIBRARY_QUERY, filter.keyword());
        model.addAttribute(ATTR_LIBRARY_SIZE, DEFAULT_EXAM_PAGE_SIZE);
        populateBadgeCounts(model, userId);
        return VIEW_LIBRARY;
    }

    /**
     * Sets all five kind badges. The serving controller owns every badge on the
     * page, so setting only the new one would render the other four as {@code 0}
     * — reading to the lecturer as "you have no documents".
     *
     * <p>The exam badge counts the lecturer's whole owned set rather than the
     * page just rendered, so narrowing the filter does not shrink the badge.
     */
    private void populateBadgeCounts(Model model, Long userId) {
        // Reuses the lessons rail's aggregate view purely for its counts; the
        // page it carries is discarded, the exam page above is what renders.
        LibraryLessonsPageView counts =
                templateService.listLessons(userId, null, null, 0, 1);
        badgeCounts.populate(model, userId, counts.totalCount(), counts.documentCount(),
                counts.videoCount(), counts.lessonCount());
    }

    /**
     * Deep-copies an owned exam into a class the lecturer leads. JSON so the
     * one-step picker can finish without leaving the rail.
     */
    @PostMapping(value = "/{id}" + PATH_LIBRARY_TEST_CLONE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> clone(@PathVariable Long id,
                                   @RequestParam(name = PARAM_EXAM_TARGET_CLASS,
                                           required = false) Long targetClassId,
                                   @AuthenticationPrincipal UlpUserDetails user) {
        try {
            Long newId = examCloneService.cloneToClass(id, targetClassId, user.getId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", MSG_EXAM_CLONE_OK);
            body.put("testId", newId);
            return ResponseEntity.ok(body);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to clone exam {} for user {}", id, user.getId(), ex);
            return internalError();
        }
    }

    /**
     * Soft-deletes an owned exam. A 400 carrying the explanatory message is the
     * blocked case (the exam already has attempts), not a malformed request.
     */
    @PostMapping(value = "/{id}" + PATH_LIBRARY_TEST_DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> delete(@PathVariable Long id,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            examCloneService.softDelete(id, user.getId());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("message", MSG_EXAM_DELETED);
            return ResponseEntity.ok(body);
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete exam {} for user {}", id, user.getId(), ex);
            return internalError();
        }
    }

    /**
     * Page-independent query params the pager must preserve. A LinkedHashMap
     * because {@code Map.of} rejects null values and the class dimension is
     * absent whenever the filter spans every class.
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
}
