package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Lesson;
import com.ulp.entities.LessonActivity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.controller.support.ActivityRowMapper;
import com.ulp.features.lessons.controller.support.MutationFailureHandler;
import com.ulp.features.lessons.dto.LessonDtos.LessonActivityRow;
import com.ulp.features.lessons.dto.LessonDtos.LessonForm;
import com.ulp.features.lessons.dto.LessonDtos.LessonReorderRequest;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.repository.LessonActivityRepository;
import com.ulp.features.lessons.repository.LessonRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsPublishService;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * Controller for the Lesson CRUD endpoints inside a class's lessons tab
 * (ULP-4.0b).
 *
 * <p>Create / edit run as full-page forms following the
 * {@link com.ulp.features.lessons.controller.SectionsController} pattern:
 * GET renders the form, POST binds {@link LessonForm} with
 * {@code @Valid + BindingResult}, validation errors re-render with inline
 * messages, success redirects (create → lessons tab, edit → same edit
 * page) with a flash toast. Publish / unpublish are form-POSTs that just
 * call into the service and redirect back; delete + reorder remain JSON
 * endpoints so the lesson list can mutate in place.
 *
 * <p>Authorization runs at two layers: this class is protected by
 * {@code @PreAuthorize(PREAUTH_LECTURER_OR_ABOVE)} (blocks STUDENT and
 * anonymous), and {@link LessonsService} additionally enforces that the
 * requesting user owns the class AND that the {@code sectionId} actually
 * belongs to {@code classId} — the latter blocks cross-class enumeration
 * attempts (lecturer A POSTing to lecturer B's section via the path
 * variables).
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/sections/{sectionId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonsController {

    private static final Logger log = LoggerFactory.getLogger(LessonsController.class);

    // ── Flash messages (Vietnamese UI text — local to this controller) ─
    private static final String MSG_LESSON_CREATE_FAILED  = "Tạo bài giảng thất bại, vui lòng thử lại.";
    private static final String MSG_LESSON_UPDATE_FAILED  = "Cập nhật bài giảng thất bại, vui lòng thử lại.";
    private static final String MSG_LESSON_PUBLISH_FAILED = "Xuất bản bài giảng thất bại, vui lòng thử lại.";

    private final LessonsService lessonsService;
    private final LessonsPublishService publishService;
    private final ClassesService classesService;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final LessonActivityRepository activityRepository;

    public LessonsController(LessonsService lessonsService,
                             LessonsPublishService publishService,
                             ClassesService classesService,
                             SectionRepository sectionRepository,
                             LessonRepository lessonRepository,
                             LessonActivityRepository activityRepository) {
        this.lessonsService = lessonsService;
        this.publishService = publishService;
        this.classesService = classesService;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.activityRepository = activityRepository;
    }

    // ── Create lesson — full-page form ─────────────────────────────────

    @GetMapping("/new")
    public String renderCreateForm(@PathVariable Long classId,
                                   @PathVariable Long sectionId,
                                   @AuthenticationPrincipal UlpUserDetails user,
                                   Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        Section section = loadSection(classId, sectionId);
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM,
                    new LessonForm("", LESSON_STATUS_DRAFT, ""));
        }
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, lessonsBaseUrl(classId, sectionId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        return VIEW_LESSON_FORM;
    }

    @PostMapping
    public String createLesson(@PathVariable Long classId,
                               @PathVariable Long sectionId,
                               @Valid @ModelAttribute("form") LessonForm form,
                               BindingResult result,
                               @AuthenticationPrincipal UlpUserDetails user,
                               Model model,
                               RedirectAttributes ra) {
        if (result.hasErrors()) {
            return reRenderForm(classId, sectionId, user, model, MODE_CREATE,
                    lessonsBaseUrl(classId, sectionId));
        }
        try {
            lessonsService.create(classId, sectionId,
                    form.title().trim(),
                    form.status(),
                    form.contentHtml(),
                    user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_CREATE_FAILED, log,
                    "Failed to create lesson in class " + classId
                            + " / section " + sectionId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_CREATED);
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }

    // ── Edit lesson — full-page form ───────────────────────────────────

    @GetMapping("/{lessonId}/edit")
    public String renderEditForm(@PathVariable Long classId,
                                 @PathVariable Long sectionId,
                                 @PathVariable Long lessonId,
                                 @RequestParam(defaultValue = TAB_INFO) String tab,
                                 @RequestParam(defaultValue = "0") int page,
                                 @AuthenticationPrincipal UlpUserDetails user,
                                 Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        Section section = loadSection(classId, sectionId);
        Lesson lesson = lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new LessonForm(
                    lesson.getTitle(),
                    lesson.getStatus(),
                    lesson.getContentRichtext() == null ? "" : lesson.getContentRichtext()));
        }
        String activeDetailTab = TAB_HISTORY.equals(tab) ? TAB_HISTORY : TAB_INFO;
        // Eager-load the activity page so the tab toggle is purely client-side.
        model.addAttribute(ATTR_ACTIVITY_PAGE,
                loadActivityPage(lessonId, Math.max(page, 0)));
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_LESSON, lesson);
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);
        model.addAttribute(ATTR_FORM_ACTION, lessonEditUrl(classId, sectionId, lessonId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        model.addAttribute(ATTR_EDIT_BASE_URL, lessonEditUrl(classId, sectionId, lessonId));
        return VIEW_LESSON_FORM;
    }

    @PostMapping("/{lessonId}/edit")
    public String editLesson(@PathVariable Long classId,
                             @PathVariable Long sectionId,
                             @PathVariable Long lessonId,
                             @Valid @ModelAttribute("form") LessonForm form,
                             BindingResult result,
                             @AuthenticationPrincipal UlpUserDetails user,
                             Model model,
                             RedirectAttributes ra) {
        if (result.hasErrors()) {
            // Reload section + lesson so the form template can render header context.
            Section section = loadSection(classId, sectionId);
            Lesson lesson = lessonRepository.findByIdAndSectionId(lessonId, sectionId)
                    .orElseThrow(() -> new EntityNotFoundException(MSG_LESSON_NOT_FOUND));
            model.addAttribute(ATTR_SECTION, section);
            model.addAttribute(ATTR_LESSON, lesson);
            // Include the activity page on re-render so the history tab is populated.
            model.addAttribute(ATTR_ACTIVITY_PAGE, loadActivityPage(lessonId, 0));
            return reRenderForm(classId, sectionId, user, model, MODE_EDIT,
                    lessonEditUrl(classId, sectionId, lessonId));
        }
        try {
            lessonsService.update(classId, sectionId, lessonId,
                    form.title().trim(),
                    form.status(),
                    form.contentHtml(),
                    user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_UPDATE_FAILED, log,
                    "Failed to update lesson " + lessonId + " in class " + classId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_UPDATED);
        // Stay on the edit page so the newly-appended history rows are
        // visible without an extra click.
        return "redirect:" + lessonEditUrl(classId, sectionId, lessonId);
    }

    // ── Publish / unpublish — form-POST + redirect ─────────────────────

    @PostMapping("/{lessonId}/publish")
    public String publishLesson(@PathVariable Long classId,
                                @PathVariable Long sectionId,
                                @PathVariable Long lessonId,
                                @AuthenticationPrincipal UlpUserDetails user,
                                RedirectAttributes ra) {
        try {
            publishService.publish(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_PUBLISHED);
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_PUBLISH_FAILED, log,
                    "Failed to publish lesson " + lessonId + " in class " + classId);
        }
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }

    @PostMapping("/{lessonId}/unpublish")
    public String unpublishLesson(@PathVariable Long classId,
                                  @PathVariable Long sectionId,
                                  @PathVariable Long lessonId,
                                  @AuthenticationPrincipal UlpUserDetails user,
                                  RedirectAttributes ra) {
        try {
            publishService.unpublish(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_LESSON_UNPUBLISHED);
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex,
                    lessonsTabUrl(classId, sectionId), ra,
                    MSG_LESSON_PUBLISH_FAILED, log,
                    "Failed to unpublish lesson " + lessonId + " in class " + classId);
        }
        return "redirect:" + lessonsTabUrl(classId, sectionId);
    }

    // ── Delete + reorder — JSON endpoints ──────────────────────────────

    @DeleteMapping(value = "/{lessonId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AjaxResult> deleteLesson(@PathVariable Long classId,
                                                   @PathVariable Long sectionId,
                                                   @PathVariable Long lessonId,
                                                   @AuthenticationPrincipal UlpUserDetails user) {
        try {
            lessonsService.delete(classId, sectionId, lessonId,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete lesson {} in class {} / section {}",
                    lessonId, classId, sectionId, ex);
            return internalError();
        }
    }

    @PostMapping(value = "/reorder",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AjaxResult> reorderLessons(@PathVariable Long classId,
                                                     @PathVariable Long sectionId,
                                                     @RequestBody(required = false) LessonReorderRequest body,
                                                     @AuthenticationPrincipal UlpUserDetails user) {
        // Authoritative validation lives in LessonsService.reorder — forward
        // the payload (or null) so the user sees a consistent error message
        // regardless of which guard tripped.
        List<Long> orderedIds = body == null ? null : body.orderedIds();
        try {
            lessonsService.reorder(classId, sectionId, orderedIds,
                    user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to reorder lessons in class {} / section {}",
                    classId, sectionId, ex);
            return internalError();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Section loadSection(Long classId, Long sectionId) {
        return sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
    }

    /** Builds the base lessons URL for a section (used as the POST target for create). */
    private static String lessonsBaseUrl(Long classId, Long sectionId) {
        return URL_CLASSES_LIST + "/" + classId
                + "/sections/" + sectionId + "/lessons";
    }

    /** Builds the canonical edit URL for a single lesson. */
    private static String lessonEditUrl(Long classId, Long sectionId, Long lessonId) {
        return lessonsBaseUrl(classId, sectionId) + "/" + lessonId + "/edit";
    }

    /** Builds the lessons tab URL for the parent class, preselecting the section. */
    private static String lessonsTabUrl(Long classId, Long sectionId) {
        return URL_CLASSES_LIST + "/" + classId + "/lessons?section=" + sectionId;
    }

    /**
     * Common form re-render path used by both create and edit on validation
     * failure. Re-populates the class + section context the template needs.
     */
    private String reRenderForm(Long classId, Long sectionId, UlpUserDetails user,
                                Model model, String mode, String formAction) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        // The section may already be in the model (edit re-render path adds
        // it before calling here); only load when absent.
        if (!model.containsAttribute(ATTR_SECTION)) {
            model.addAttribute(ATTR_SECTION, loadSection(classId, sectionId));
        }
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_MODE, mode);
        // Validation always re-renders on the "info" tab — the history tab
        // has no form to submit.
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_FORM_ACTION, formAction);
        model.addAttribute(ATTR_CANCEL_URL, lessonsTabUrl(classId, sectionId));
        if (MODE_EDIT.equals(mode)) {
            model.addAttribute(ATTR_EDIT_BASE_URL, formAction);
        }
        return VIEW_LESSON_FORM;
    }

    /**
     * Loads the activity page for a lesson, mapping each {@link LessonActivity}
     * row onto a {@link LessonActivityRow} DTO with a Vietnamese type label
     * so the template stays free of type-to-label switches.
     */
    private Page<LessonActivityRow> loadActivityPage(Long lessonId, int page) {
        Pageable pageable = PageRequest.of(page, DEFAULT_HISTORY_PAGE_SIZE);
        Page<LessonActivity> raw = activityRepository
                .findByLessonIdOrderByCreatedAtDesc(lessonId, pageable);
        List<LessonActivityRow> rows = new ArrayList<>(raw.getNumberOfElements());
        for (LessonActivity a : raw.getContent()) {
            rows.add(new LessonActivityRow(a.getId(), a.getType(),
                    ActivityRowMapper.lessonLabel(a.getType()),
                    a.getDescription(), a.getCreatedAt()));
        }
        return new PageImpl<>(rows, pageable, raw.getTotalElements());
    }
}
