package com.ulp.features.tests.controller;

import com.ulp.features.tests.dto.TestDtos.ClassTestsView;
import com.ulp.features.tests.service.TestCatalogService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.ulp.common.IConstant.ATTR_PAGER_PARAMS;
import static com.ulp.common.IConstant.ATTR_VIEW;
import static com.ulp.common.IConstant.VIEW_STUDENT_CLASS_TESTS;

/**
 * Student-facing SSR controller for a single class's PUBLISHED exams under
 * {@code /my/classes/{classId}/tests}. Mirrors the class lessons shell: the
 * shared class sidebar on the left, a searchable + paginated list of the
 * class's tests on the right.
 *
 * <p>Access is gated in {@link TestCatalogService#listClassTests} — the caller
 * must be ACTIVE-enrolled in the class, else 404 (existence never leaked).
 */
@Controller
@RequestMapping("/my/classes/{classId}/tests")
@PreAuthorize("isAuthenticated()")
public class StudentClassTestsController {

    private final TestCatalogService catalogService;

    public StudentClassTestsController(TestCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Lists the class's PUBLISHED exams, title-filtered by {@code ?q} and paged. */
    @GetMapping
    public String list(@PathVariable Long classId,
                       @RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "page", defaultValue = "0") int page,
                       @AuthenticationPrincipal UlpUserDetails user, Model model) {
        ClassTestsView view = catalogService.listClassTests(classId, user.getId(), q, page);
        model.addAttribute(ATTR_VIEW, view);
        // Preserve the search term across pager links (null-safe: PageWindow skips blanks).
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", view.query());
        model.addAttribute(ATTR_PAGER_PARAMS, params);
        return VIEW_STUDENT_CLASS_TESTS;
    }
}
