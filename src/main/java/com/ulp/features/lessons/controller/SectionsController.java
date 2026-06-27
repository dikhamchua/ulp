package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.controller.support.MutationFailureHandler;
import com.ulp.features.lessons.controller.support.SectionActivityPageLoader;
import com.ulp.features.lessons.controller.support.SectionFormSupport;
import com.ulp.features.lessons.dto.SectionDtos.SectionForm;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.SectionsService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static com.ulp.common.IConstant.*;
import static com.ulp.features.lessons.controller.support.SectionFormSupport.lessonsUrl;
import static com.ulp.features.lessons.controller.support.SectionFormSupport.sectionEditUrl;

/**
 * Section CRUD form endpoints for the lessons tab (ULP-4.0a).
 *
 * <p>Create / rename run as full-page forms following the pattern of
 * {@code ClassesController.createForm/create}: GET renders the form, POST
 * binds {@link SectionForm} with {@code @Valid + BindingResult}. The
 * lessons-tab landing page lives on {@link LessonsTabController}; JSON
 * mutations (delete, reorder) live on {@link SectionsApiController}.
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

    private static final String VIEW_SECTION_FORM = "classes/section-form";

    private static final String MSG_SECTION_CREATED        = "Đã tạo chương";
    private static final String MSG_SECTION_CREATE_FAILED  = "Tạo chương thất bại, vui lòng thử lại.";
    private static final String MSG_SECTION_RENAMED        = "Đã đổi tên chương";
    private static final String MSG_SECTION_RENAME_FAILED  = "Đổi tên chương thất bại, vui lòng thử lại.";

    private final SectionsService sectionsService;
    private final ClassesService classesService;
    private final SectionRepository sectionRepository;
    private final SectionActivityPageLoader activityPageLoader;
    private final SectionFormSupport formSupport;

    public SectionsController(SectionsService sectionsService,
                              ClassesService classesService,
                              SectionRepository sectionRepository,
                              SectionActivityPageLoader activityPageLoader,
                              SectionFormSupport formSupport) {
        this.sectionsService = sectionsService;
        this.classesService = classesService;
        this.sectionRepository = sectionRepository;
        this.activityPageLoader = activityPageLoader;
        this.formSupport = formSupport;
    }

    // ── Section create — full-page form ────────────────────────────────

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

    @PostMapping("/sections")
    public String createSection(@PathVariable Long classId,
                                @Valid @ModelAttribute("form") SectionForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            return formSupport.reRenderForm(classId, user, model, MODE_CREATE,
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
     * The {@code ?tab=info|history} query parameter is honoured so a deep
     * link or a reload lands on the correct panel.
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
        // Eager-load activity so the tab strip can switch panels without a round-trip.
        model.addAttribute(ATTR_ACTIVITY_PAGE,
                activityPageLoader.load(sectionId, Math.max(page, 0)));
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_SECTION, section);
        model.addAttribute(ATTR_MODE, MODE_EDIT);
        model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeDetailTab);
        model.addAttribute(ATTR_FORM_ACTION, sectionEditUrl(classId, sectionId));
        model.addAttribute(ATTR_CANCEL_URL, lessonsUrl(classId));
        model.addAttribute(ATTR_EDIT_BASE_URL, sectionEditUrl(classId, sectionId));
        return VIEW_SECTION_FORM;
    }

    @PostMapping("/sections/{sectionId}/edit")
    public String renameSection(@PathVariable Long classId,
                                @PathVariable Long sectionId,
                                @Valid @ModelAttribute("form") SectionForm form,
                                BindingResult result,
                                @AuthenticationPrincipal UlpUserDetails user,
                                Model model,
                                RedirectAttributes ra) {
        if (result.hasErrors()) {
            // Reload section + activity so the form template renders the
            // current title and the history tab on re-render.
            Section section = sectionRepository.findByIdAndClassId(sectionId, classId)
                    .orElseThrow(() -> new EntityNotFoundException(MSG_SECTION_NOT_FOUND));
            model.addAttribute(ATTR_SECTION, section);
            model.addAttribute(ATTR_ACTIVITY_PAGE, activityPageLoader.load(sectionId, 0));
            return formSupport.reRenderForm(classId, user, model, MODE_EDIT,
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
        // Stay on the edit page so the newly-appended RENAMED row appears in history.
        return "redirect:" + sectionEditUrl(classId, sectionId);
    }
}
