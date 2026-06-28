package com.ulp.features.student.controller;

import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.service.StudentLessonDetailService;
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
import static com.ulp.common.IConstant.ATTR_LESSON_DETAIL;
import static com.ulp.common.IConstant.ATTR_VIEW;
import static com.ulp.common.IConstant.VIEW_STUDENT_CLASS_LESSONS;
import static com.ulp.common.IConstant.VIEW_STUDENT_LESSON_DETAIL;

/**
 * Student-facing controller for
 * {@code GET /my/classes/{classId}/lessons} (ULP-4.1) and
 * {@code GET /my/classes/{classId}/lessons/{lessonId}} (ULP-4.2).
 *
 * <p>Authentication is gated by {@code SecurityConfig} ({@code /my/**}
 * → {@code isAuthenticated()}); the services perform the
 * enrollment-ACTIVE check and raise {@code EntityNotFoundException}
 * (handled centrally as HTTP 404) when the caller is not enrolled.
 *
 * <p>The {@code ?section=X} query parameter on the list endpoint selects
 * which section's lessons render in the main panel. An invalid id (does
 * not belong to this class) falls back silently to the first section
 * instead of throwing — preventing section enumeration via URL fuzzing
 * (D7).
 */
@Controller
@RequestMapping("/my/classes/{classId}/lessons")
@PreAuthorize("isAuthenticated()")
public class StudentLessonsController {

    private final StudentLessonsService studentLessonsService;
    private final StudentLessonDetailService studentLessonDetailService;

    public StudentLessonsController(StudentLessonsService studentLessonsService,
                                    StudentLessonDetailService studentLessonDetailService) {
        this.studentLessonsService = studentLessonsService;
        this.studentLessonDetailService = studentLessonDetailService;
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

    /**
     * Renders the read-only detail page for one PUBLISHED lesson at
     * {@code GET /my/classes/{classId}/lessons/{lessonId}} (ULP-4.2).
     *
     * <p>The service enforces enrollment + class + cross-class + status
     * gates and raises {@link jakarta.persistence.EntityNotFoundException}
     * (mapped to HTTP 404 by {@code GlobalExceptionHandler}) when any
     * gate fails. The controller stays thin — no extra validation here.
     */
    @GetMapping("/{lessonId}")
    public String viewLesson(@PathVariable Long classId,
                             @PathVariable Long lessonId,
                             @AuthenticationPrincipal UlpUserDetails user,
                             Model model) {
        LessonDetailView lessonDetail = studentLessonDetailService
                .getLessonDetail(classId, lessonId, user.getId());
        model.addAttribute(ATTR_LESSON_DETAIL, lessonDetail);
        return VIEW_STUDENT_LESSON_DETAIL;
    }
}

