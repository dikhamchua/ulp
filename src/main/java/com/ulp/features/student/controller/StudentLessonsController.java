package com.ulp.features.student.controller;

import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.student.service.StudentLessonDetailService;
import com.ulp.features.student.service.StudentLessonsService;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
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
 * enrollment-ACTIVE check and raise {@link EntityNotFoundException}
 * (handled centrally as HTTP 404) when the caller is not enrolled.
 *
 * <p>Two query parameters control the list endpoint:
 * <ul>
 *   <li>{@code ?section=X} — selects which section's lessons render in
 *       the rail. An invalid id falls back silently to the first section
 *       (anti-fuzzing, D7).</li>
 *   <li>{@code ?lesson=Y} — inlines that lesson's rich-text content into
 *       the main panel. Must belong to the active section and pass the
 *       same authz gates as the dedicated detail route. Invalid /
 *       cross-section ids fall back to the hero placeholder rather than
 *       throwing — again anti-fuzzing.</li>
 * </ul>
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
                       @RequestParam(value = "lesson", required = false) Long lessonParam,
                       @AuthenticationPrincipal UlpUserDetails user,
                       Model model) {
        ClassLessonsView view = studentLessonsService
                .listClassLessons(classId, user.getId());

        Long activeSectionId = resolveActiveSection(view, sectionParam);
        model.addAttribute(ATTR_VIEW, view);
        model.addAttribute(ATTR_ACTIVE_SECTION_ID, activeSectionId);

        // Inline lesson detail when ?lesson=X is provided AND it belongs to the
        // active section. Invalid lesson id falls back to the hero placeholder
        // rather than 404 — preventing URL fuzzing of detail existence.
        if (lessonParam != null && activeSectionId != null
                && lessonBelongsToSection(view, activeSectionId, lessonParam)) {
            try {
                LessonDetailView detail = studentLessonDetailService
                        .getLessonDetail(classId, lessonParam, user.getId());
                model.addAttribute(ATTR_LESSON_DETAIL, detail);
            } catch (EntityNotFoundException ignored) {
                // Silently fall back to hero placeholder — caller's enrollment
                // was already validated by listClassLessons() above.
            }
        }
        return VIEW_STUDENT_CLASS_LESSONS;
    }

    /** True when {@code lessonId} appears in the active section's lesson list. */
    private static boolean lessonBelongsToSection(ClassLessonsView view,
                                                  Long activeSectionId,
                                                  Long lessonId) {
        for (SectionWithLessons s : view.sections()) {
            if (activeSectionId.equals(s.sectionId())) {
                return s.lessons().stream().anyMatch(l -> lessonId.equals(l.id()));
            }
        }
        return false;
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
     * <p>Kept as a permalink route — deep-linking from notifications,
     * search results, or sharing surfaces the dedicated detail view. The
     * inline {@code ?lesson=} variant on the list endpoint is the
     * primary in-app navigation path.
     *
     * <p>The service enforces enrollment + class + cross-class + status
     * gates and raises {@link EntityNotFoundException} (mapped to HTTP
     * 404 by {@code GlobalExceptionHandler}) when any gate fails.
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

