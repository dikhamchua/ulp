package com.ulp.features.student.controller;

import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.service.StudentLessonsService;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import static com.ulp.common.IConstant.ATTR_ACTIVE_SECTION_ID;
import static com.ulp.common.IConstant.ATTR_VIEW;
import static com.ulp.common.IConstant.VIEW_STUDENT_CLASS_LESSONS;

/**
 * Student-facing controller for
 * {@code GET /my/classes/{classId}/lessons}.
 *
 * <p>Authentication is gated by {@code SecurityConfig} ({@code /my/**}
 * → {@code isAuthenticated()}); the service performs the
 * enrollment-ACTIVE check and raises {@code EntityNotFoundException}
 * (handled centrally as HTTP 404) when the caller is not enrolled.
 *
 * <p>The {@code ?section=X} query parameter selects which section's
 * lessons render in the main panel. An invalid id (does not belong to
 * this class) falls back silently to the first section instead of
 * throwing — preventing section enumeration via URL fuzzing (D7).
 */
@Controller
@RequestMapping("/my/classes/{classId}/lessons")
@PreAuthorize("isAuthenticated()")
public class StudentLessonsController {

    private final StudentLessonsService studentLessonsService;

    public StudentLessonsController(StudentLessonsService studentLessonsService) {
        this.studentLessonsService = studentLessonsService;
    }

    /** Renders the class's sections + PUBLISHED lessons for the student. */
    @GetMapping
    public String view(@PathVariable Long classId,
                       @RequestParam(value = "section", required = false) Long sectionParam,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        ClassLessonsView view = studentLessonsService
                .listClassLessons(classId, user.getId());

        Long activeSectionId = resolveActiveSection(view, sectionParam);
        model.addAttribute(ATTR_VIEW, view);
        model.addAttribute(ATTR_ACTIVE_SECTION_ID, activeSectionId);
        return VIEW_STUDENT_CLASS_LESSONS;
    }

    /**
     * Picks the section to render in the main panel. If the caller's
     * {@code ?section} matches a real section in this class use it;
     * otherwise default to the first section. Returns {@code null}
     * only when the class has no sections at all.
     */
    private static Long resolveActiveSection(ClassLessonsView view, Long sectionParam) {
        if (view.sections().isEmpty()) {
            return null;
        }
        if (sectionParam != null) {
            for (SectionWithLessons s : view.sections()) {
                if (sectionParam.equals(s.sectionId())) {
                    return sectionParam;
                }
            }
            // Fall through to default — invalid id renders empty main (D7).
        }
        return view.sections().get(0).sectionId();
    }
}
