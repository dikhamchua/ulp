package com.ulp.features.student.controller;

import com.ulp.features.flashcards.service.DeckService;
import com.ulp.features.student.dto.StudentLessonsDtos.ClassLessonsView;
import com.ulp.features.student.dto.StudentLessonsDtos.LessonDetailView;
import com.ulp.features.student.dto.StudentLessonsDtos.SectionWithLessons;
import com.ulp.features.progress.service.LearningProgressService;
import com.ulp.features.student.service.StudentLessonDetailService;
import com.ulp.features.student.service.StudentLessonsService;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * Student-facing controller for the class lessons list page.
 *
 * <p>The standalone detail URL
 * ({@code /my/classes/{classId}/lessons/{lessonId}}) now returns a 301
 * redirect to the canonical query-param form
 * ({@code /my/classes/{classId}/lessons?section=X&lesson=Y}).
 *
 * <p>Two query parameters control the list endpoint:
 * <ul>
 *   <li>{@code ?section=X} — selects which section's lessons render in
 *       the rail. An invalid id falls back silently to the first section
 *       (anti-fuzzing).</li>
 *   <li>{@code ?lesson=Y} — inlines that lesson's content into the main
 *       panel and switches the viewer per {@code contentType}
 *       (RICHTEXT / PDF / VIDEO). Must belong to the active section and
 *       pass the same authz gates. Invalid / cross-section ids fall
 *       back to the hero placeholder — again anti-fuzzing.</li>
 * </ul>
 */
@Controller
@RequestMapping("/my/classes/{classId}/lessons")
@PreAuthorize("isAuthenticated()")
public class StudentLessonsController {

    private static final Logger log = LoggerFactory.getLogger(StudentLessonsController.class);

    private final StudentLessonsService studentLessonsService;
    private final StudentLessonDetailService studentLessonDetailService;
    private final LearningProgressService learningProgressService;
    private final DeckService deckService;

    public StudentLessonsController(StudentLessonsService studentLessonsService,
                                    StudentLessonDetailService studentLessonDetailService,
                                    LearningProgressService learningProgressService,
                                    DeckService deckService) {
        this.studentLessonsService = studentLessonsService;
        this.studentLessonDetailService = studentLessonDetailService;
        this.learningProgressService = learningProgressService;
        this.deckService = deckService;
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
        // Surface flashcard decks shared to this class in the sidebar.
        model.addAttribute("classSharedDecks",
                deckService.listSharedForClass(classId, user.getId()));

        // Inline lesson detail when ?lesson=X is provided AND it belongs to the
        // active section. Invalid lesson id falls back to the hero placeholder
        // rather than 404 — preventing URL fuzzing of detail existence.
        if (lessonParam != null && activeSectionId != null
                && lessonBelongsToSection(view, activeSectionId, lessonParam)) {
            try {
                LessonDetailView detail = studentLessonDetailService
                        .getLessonDetail(classId, lessonParam, user.getId());
                model.addAttribute(ATTR_LESSON_DETAIL, detail);
                // Auto-record IN_PROGRESS only after the detail gates pass;
                // isolated so a progress write failure never breaks rendering.
                recordOpenedQuietly(classId, lessonParam, user.getId());
            } catch (EntityNotFoundException ignored) {
                // Silently fall back to hero placeholder — caller's enrollment
                // was already validated by listClassLessons() above.
            }
        }
        return VIEW_STUDENT_CLASS_LESSONS;
    }

    /**
     * Records the open as IN_PROGRESS, swallowing and logging any failure so
     * a progress write problem can never break lesson rendering (design D3).
     */
    private void recordOpenedQuietly(Long classId, Long lessonId, Long userId) {
        try {
            learningProgressService.recordOpened(classId, lessonId, userId);
        } catch (RuntimeException ex) {
            log.warn("Failed to record open progress for lesson {} (user {})",
                    lessonId, userId, ex);
        }
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
     * Permanently redirects the legacy standalone detail URL
     * ({@code /my/classes/{classId}/lessons/{lessonId}}) to the canonical
     * inline form ({@code /my/classes/{classId}/lessons?section=X&lesson=Y}).
     * The single-template refactor folds detail rendering into the
     * 3-column list view so PDFs and videos share the surrounding
     * sidebar + lesson rail.
     *
     * <p>Delegates to {@link StudentLessonDetailService#getLessonDetail}
     * for ALL authz gates (enrollment ACTIVE, class live, cross-class,
     * lesson PUBLISHED). Any gate failure raises {@code EntityNotFoundException}
     * mapped to HTTP 404 — the redirect never leaks lesson existence to
     * non-enrolled callers or for DRAFT lessons.
     *
     * @return HTTP 301 with the rewritten {@code Location} header
     */
    @GetMapping("/{lessonId}")
    public ResponseEntity<Void> redirectStandaloneLesson(@PathVariable Long classId,
                                                         @PathVariable Long lessonId,
                                                         @AuthenticationPrincipal UlpUserDetails user) {
        // Reuse the detail service so every gate (enrollment / class /
        // cross-class / PUBLISHED) runs unchanged. sectionId comes from
        // the resolved view so the query-param URL pre-selects the right
        // sidebar entry.
        LessonDetailView detail = studentLessonDetailService
                .getLessonDetail(classId, lessonId, user.getId());

        String location = studentLessonUrl(classId, detail.sectionId(), detail.lessonId());
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, location);
        return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
    }

    /**
     * Builds the canonical student lesson detail query-param URL.
     * Kept as a helper (not in IConstant) because it carries path variables.
     */
    private static String studentLessonUrl(Long classId, Long sectionId, Long lessonId) {
        return "/my/classes/" + classId + "/lessons"
                + "?section=" + sectionId + "&lesson=" + lessonId;
    }
}

