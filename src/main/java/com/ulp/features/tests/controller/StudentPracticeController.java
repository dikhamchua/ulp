package com.ulp.features.tests.controller;

import com.ulp.features.tests.dto.TestDtos.PracticeForm;
import com.ulp.features.tests.dto.TestDtos.PracticeView;
import com.ulp.features.tests.dto.TestDtos.ReadinessView;
import com.ulp.features.tests.service.PracticeTestService;
import com.ulp.features.tests.service.ReadinessService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.ATTR_FLASH_ERROR;
import static com.ulp.common.IConstant.ATTR_FLASH_SUCCESS;
import static com.ulp.common.IConstant.ATTR_PRACTICE;
import static com.ulp.common.IConstant.ATTR_READINESS;
import static com.ulp.common.IConstant.BASE_MY_TESTS;
import static com.ulp.common.IConstant.MSG_PRACTICE_CREATED;
import static com.ulp.common.IConstant.VIEW_TEST_PRACTICE_NEW;
import static com.ulp.common.IConstant.VIEW_TEST_READINESS;

/**
 * Student-facing SSR controller for the personal practice generator and the
 * exam readiness dashboard, both under {@code /my/tests}.
 */
@Controller
@RequestMapping(BASE_MY_TESTS)
@PreAuthorize("isAuthenticated()")
public class StudentPracticeController {

    private final PracticeTestService practiceService;
    private final ReadinessService readinessService;

    public StudentPracticeController(PracticeTestService practiceService,
                                     ReadinessService readinessService) {
        this.practiceService = practiceService;
        this.readinessService = readinessService;
    }

    /** Renders the practice-generation form (accessible source scopes). */
    @GetMapping("/practice/new")
    public String practiceForm(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        PracticeView practice = practiceService.sources(user.getId());
        model.addAttribute(ATTR_PRACTICE, practice);
        return VIEW_TEST_PRACTICE_NEW;
    }

    /** Creates a personal practice test then redirects into it. */
    @PostMapping("/practice")
    public String createPractice(@RequestParam(name = "sourceClassId", required = false) Long sourceClassId,
                                 @RequestParam(name = "sourceTestId", required = false) Long sourceTestId,
                                 @RequestParam(name = "count", defaultValue = "10") int count,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 RedirectAttributes ra) {
        try {
            Long id = practiceService.create(user.getId(),
                    new PracticeForm(sourceClassId, sourceTestId, count));
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_PRACTICE_CREATED);
            return "redirect:" + BASE_MY_TESTS + "/" + id + "/take";
        } catch (IllegalArgumentException ex) {
            // Empty pool / invalid source → surface via toast on the form.
            ra.addFlashAttribute(ATTR_FLASH_ERROR, ex.getMessage());
            return "redirect:" + BASE_MY_TESTS + "/practice/new";
        }
    }

    /** Renders the real-time exam readiness dashboard. */
    @GetMapping("/readiness")
    public String readiness(@AuthenticationPrincipal UlpUserDetails user, Model model) {
        ReadinessView readiness = readinessService.compute(user.getId());
        model.addAttribute(ATTR_READINESS, readiness);
        return VIEW_TEST_READINESS;
    }
}
