package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.entities.SectionActivity;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.controller.support.ActivityRowMapper;
import com.ulp.features.lessons.controller.support.MutationFailureHandler;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.dto.SectionDtos.ActivityRow;
import com.ulp.features.lessons.dto.SectionDtos.AjaxResult;
import com.ulp.features.lessons.dto.SectionDtos.ReorderRequest;
import com.ulp.features.lessons.dto.SectionDtos.SectionForm;
import com.ulp.features.lessons.dto.SectionDtos.SectionRow;
import com.ulp.features.lessons.repository.SectionActivityRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.lessons.service.SectionsService;
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
import java.util.Collections;
import java.util.List;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;

/**
 * Controller for the lessons tab (ULP-4.0a) — section CRUD only. Lessons
 * proper land in ULP-4.0b.
 *
 * <p>Create / rename run as full-page forms following the pattern of
 * {@code ClassesController.createForm/create}: GET renders the form, POST
 * binds {@link SectionForm} with {@code @Valid + BindingResult}, validation
 * errors re-render the form with inline messages, success redirects back to
 * the list with a flash toast. The page-based flow replaced the original
 * AJAX modal because the {@code <dialog>} element rendered uncentered in
 * the lessons tab (no shared modal CSS loaded).
 *
 * <p>Delete and reorder remain JSON endpoints — they need to mutate state
 * without a full reload so the section list stays responsive (drag-and-
 * drop especially can't redirect).
 *
 * <p>Authorization runs at two layers: this class is protected by
 * {@code @PreAuthorize(PREAUTH_LECTURER_OR_ABOVE)} (blocks STUDENT and
 * anonymous), and {@link SectionsService} additionally rejects lecturers
 * who do not own the requested class via
 * {@link ClassesService#getEditable}.
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class SectionsController {

    private static final Logger log = LoggerFactory.getLogger(SectionsController.class);

    // ── View names ────────────────────────────────────────────────────
    private static final String VIEW_LESSONS      = "classes/detail-lessons";
    private static final String VIEW_SECTION_FORM = "classes/section-form";

    // ── Local model attribute keys (specific to this controller) ──────
    private static final String ATTR_SECTIONS             = "sections";
    private static final String ATTR_CAN_EDIT             = "canEdit";
    private static final String ATTR_SELECTED_SECTION_ID  = "selectedSectionId";
    private static final String ATTR_SELECTED_SECTION     = "selectedSection";

    // ── Flash messages (Vietnamese UI text — local to this controller) ─
    private static final String MSG_SECTION_CREATED        = "Đã tạo chương";
    private static final String MSG_SECTION_CREATE_FAILED  = "Tạo chương thất bại, vui lòng thử lại.";
    private static final String MSG_SECTION_RENAMED        = "Đã đổi tên chương";
    private static final String MSG_SECTION_RENAME_FAILED  = "Đổi tên chương thất bại, vui lòng thử lại.";

    private final SectionsService sectionsService;
    private final LessonsService lessonsService;
    private final ClassesService classesService;
    private final SectionRepository sectionRepository;
    private final SectionActivityRepository activityRepository;

    public SectionsController(SectionsService sectionsService,
                              LessonsService lessonsService,
                              ClassesService classesService,
                              SectionRepository sectionRepository,
                              SectionActivityRepository activityRepository) {
        this.sectionsService = sectionsService;
        this.lessonsService = lessonsService;
        this.classesService = classesService;
        this.sectionRepository = sectionRepository;
        this.activityRepository = activityRepository;
    }

    /**
     * Renders the lessons tab page with the current section list.
     *
     * <p>The optional {@code section} query parameter selects a folder in the
     * left column and drives the content header on the right. When present
     * the section is validated to belong to {@code classId} via
     * {@link SectionRepository#findByIdAndClassId}; if it doesn't (stale link,
     * bad URL), we silently fall back to "all lessons" instead of returning
     * 404 so the UX stays soft. The model exposes both a nullable
     * {@code selectedSectionId} and a nullable {@code selectedSection} entity
     * — the template needs the entity to render the "Bài giảng của …" header.
     */
    @GetMapping
    public String renderLessonsPage(@PathVariable Long classId,
                                    @RequestParam(required = false) Long section,
                                    @AuthenticationPrincipal UlpUserDetails user,
                                    Model model) {
        ClassEntity clazz = classesService.getViewable(classId, user.getId(), user.getRole());
        List<SectionRow> sections = sectionsService.listForClass(
                classId, user.getId(), user.getRole());
        boolean canEdit = classesService.isEditableBy(clazz, user.getId(), user.getRole());

        // Soft validation: if the section doesn't belong to this class, treat
        // it as "no selection" rather than 404 so cross-class links degrade
        // gracefully.
        Section selectedSection = null;
        if (section != null) {
            selectedSection = sectionRepository.findByIdAndClassId(section, classId)
                    .orElse(null);
        }
        Long selectedSectionId = selectedSection != null ? selectedSection.getId() : null;

        // Lessons for the content column — fetched only when a section is
        // selected so the "All lessons" landing page stays cheap. The list
        // is empty when no section is picked; the template uses the
        // selectedSectionId attribute to decide which empty state to show.
        List<LessonRow> lessons = selectedSection != null
                ? lessonsService.listForSection(classId, selectedSection.getId(),
                        user.getId(), user.getRole())
                : Collections.emptyList();

        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_LESSONS);
        model.addAttribute(ATTR_SECTIONS, sections);
        model.addAttribute(ATTR_CAN_EDIT, canEdit);
        model.addAttribute(ATTR_SELECTED_SECTION_ID, selectedSectionId);
        model.addAttribute(ATTR_SELECTED_SECTION, selectedSection);
        model.addAttribute(ATTR_LESSONS, lessons);
        return VIEW_LESSONS;
    }

    // ── Section create — full-page form ────────────────────────────────

    /**
     * Renders the create-section form. The class is loaded through
     * {@link ClassesService#getEditable} so a non-owning lecturer is
     * rejected up front (rather than after submitting the form).
     */
    @GetMapping("/new")
    public String renderCreateForm(@PathVariable Long classId,
                                   @AuthenticationPrincipal UlpUserDetails user,
                                   Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new SectionForm(""));
        }
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_MODE, MODE_CREATE);
        model.addAttribute(ATTR_FORM_ACTION, lessonsUrl(classId) + "/sections");
        model.addAttribute(ATTR_CANCEL_URL, lessonsUrl(classId));
        return VIEW_SECTION_FORM;
    }

    /**
     * Submits the create-section form. Re-renders the form with inline
     * errors on validation failure; on success redirects to the lessons
     * tab with a flash toast.
     */
    @PostMapping("/sections")
    public String createSection(@PathVariable Long classId,
                                @Valid @ModelAttribute("form") SectionForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            return reRenderForm(classId, user, model, MODE_CREATE,
                    lessonsUrl(classId) + "/sections");
        }
        try {
            sectionsService.create(classId, form.title().trim(),
                    user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex, lessonsUrl(classId), ra,
                    MSG_SECTION_CREATE_FAILED, log,
                    "Failed to create section in class {}", classId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SECTION_CREATED);
        return "redirect:" + lessonsUrl(classId);
    }

    // ── Section rename — full-page form ────────────────────────────────

    /**
     * Renders the rename-section form pre-filled with the current title.
     *
     * <p>The page hosts two sub-tabs (Info / Lịch sử) that switch entirely
     * client-side — both panels are rendered in the initial response and
     * the JS in {@code section-form.html} toggles their visibility. The
     * {@code ?tab=info|history} query parameter is still honoured so a
     * deep link or a reload after the user changed tabs lands on the
     * correct panel. Unknown values fall back to {@code info}.
     */
    @GetMapping("/sections/{sectionId}/edit")
    public String renderRenameForm(@PathVariable Long classId,
                                   @PathVariable Long sectionId,
                                   @RequestParam(defaultValue = TAB_INFO) String tab,
                                   @RequestParam(defaultValue = "0") int page,
                                   @AuthenticationPrincipal UlpUserDetails user,
                                   Model model) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
        if (!model.containsAttribute(ATTR_FORM)) {
            model.addAttribute(ATTR_FORM, new SectionForm(section.getTitle()));
        }
        String activeDetailTab = TAB_HISTORY.equals(tab) ? TAB_HISTORY : TAB_INFO;
        // Eager-load the activity page on every visit so the tab strip can
        // switch panels without a round-trip. The DB cost is small — the
        // index `idx_asec_section (section_id, created_at)` covers it and
        // the page size is capped at DEFAULT_HISTORY_PAGE_SIZE.
        model.addAttribute(ATTR_ACTIVITY_PAGE,
                loadActivityPage(sectionId, Math.max(page, 0)));
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);
        model.addAttribute(ATTR_FORM_ACTION, sectionEditUrl(classId, sectionId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsUrl(classId));
        model.addAttribute(ATTR_EDIT_BASE_URL, sectionEditUrl(classId, sectionId));
        return VIEW_SECTION_FORM;
    }

    /** Submits the rename-section form. Same re-render / redirect contract as create. */
    @PostMapping("/sections/{sectionId}/edit")
    public String renameSection(@PathVariable Long classId,
                                @PathVariable Long sectionId,
                                @Valid @ModelAttribute("form") SectionForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            // Reload the section so the form template can show its current
            // title (for breadcrumbs / header) on re-render.
            Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                    .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
            model.addAttribute(ATTR_SECTION, section);
            // Activity page is rendered alongside the form, so include it on
            // the re-render path as well — otherwise the history tab would
            // be empty after a validation failure.
            model.addAttribute(ATTR_ACTIVITY_PAGE, loadActivityPage(sectionId, 0));
            return reRenderForm(classId, user, model, MODE_EDIT,
                    sectionEditUrl(classId, sectionId));
        }
        try {
            sectionsService.rename(classId, sectionId, form.title().trim(),
                    user.getId(), user.getRole());
        } catch (RuntimeException ex) {
            return MutationFailureHandler.handle(ex, lessonsUrl(classId), ra,
                    MSG_SECTION_RENAME_FAILED, log,
                    "Failed to rename section " + sectionId + " in class {}", classId);
        }
        ra.addFlashAttribute(ATTR_FLASH_SUCCESS, MSG_SECTION_RENAMED);
        // Stay on the edit page so the lecturer can see the newly-appended
        // RENAMED row appear in the history tab without navigating back.
        return "redirect:" + sectionEditUrl(classId, sectionId);
    }

    // ── Delete + reorder remain JSON (AJAX from sections.js) ──────────

    /** Soft-deletes a section. Returns JSON so the list can update in-place. */
    @DeleteMapping(value = "/sections/{sectionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AjaxResult> deleteSection(@PathVariable Long classId,
                                                    @PathVariable Long sectionId,
                                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            sectionsService.delete(classId, sectionId, user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to delete section {} in class {}", sectionId, classId, ex);
            return internalError();
        }
    }

    /** Persists a new order for the class's sections (drag-and-drop API). */
    @PostMapping(value = "/sections/reorder",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<AjaxResult> reorderSections(@PathVariable Long classId,
                                                       @RequestBody(required = false) ReorderRequest body,
                                                       @AuthenticationPrincipal UlpUserDetails user) {
        // Authoritative validation lives in SectionsService.reorder — it
        // raises IllegalArgumentException with a user-facing message for
        // every invalid shape (null list, wrong size, duplicate ids,
        // unknown ids). Forward the payload (or null) and let the service
        // own the contract so the user sees a consistent error message
        // regardless of which guard tripped.
        List<Long> orderedIds = body == null ? null : body.orderedIds();
        try {
            sectionsService.reorder(
                    classId, orderedIds, user.getId(), user.getRole());
            return ResponseEntity.ok(AjaxResult.success());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to reorder sections in class {}", classId, ex);
            return internalError();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Builds the canonical URL for the lessons tab of a given class. */
    private static String lessonsUrl(Long classId) {
        return URL_CLASSES_LIST + "/" + classId + "/lessons";
    }

    /** Builds the canonical edit URL for a section within a class. */
    private static String sectionEditUrl(Long classId, Long sectionId) {
        return lessonsUrl(classId) + "/sections/" + sectionId + "/edit";
    }

    /**
     * Common form re-render path used by both create and edit on validation
     * failure. Re-populates the {@code clazz} + sidebar attributes the
     * template needs.
     */
    private String reRenderForm(Long classId, UlpUserDetails user,
                                Model model, String mode, String formAction) {
        ClassEntity clazz = classesService.getEditable(classId, user.getId(), user.getRole());
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_MODE, mode);
        // Validation always re-renders on the "info" tab — the history tab
        // has no form to submit. Set the attribute eagerly so the template
        // can compare without a null check.
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, TAB_INFO);
        model.addAttribute(ATTR_FORM_ACTION, formAction);
        model.addAttribute(ATTR_CANCEL_URL, lessonsUrl(classId));
        if (MODE_EDIT.equals(mode)) {
            model.addAttribute(ATTR_EDIT_BASE_URL, formAction);
        }
        return VIEW_SECTION_FORM;
    }

    /**
     * Loads the activity page for a section, mapping each {@link SectionActivity}
     * row onto an {@link ActivityRow} DTO with a Vietnamese type label so
     * the template stays free of type-to-label switches.
     */
    private Page<ActivityRow> loadActivityPage(Long sectionId, int page) {
        Pageable pageable = PageRequest.of(page, DEFAULT_HISTORY_PAGE_SIZE);
        Page<SectionActivity> raw = activityRepository
                .findBySectionIdOrderByCreatedAtDesc(sectionId, pageable);
        List<ActivityRow> rows = new ArrayList<>(raw.getNumberOfElements());
        for (SectionActivity a : raw.getContent()) {
            rows.add(new ActivityRow(a.getId(), a.getType(),
                    ActivityRowMapper.sectionLabel(a.getType()),
                    a.getDescription(), a.getCreatedAt()));
        }
        return new PageImpl<>(rows, pageable, raw.getTotalElements());
    }
}
