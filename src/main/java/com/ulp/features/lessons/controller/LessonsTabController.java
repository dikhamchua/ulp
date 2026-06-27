package com.ulp.features.lessons.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.lessons.dto.LessonDtos.LessonRow;
import com.ulp.features.lessons.dto.SectionDtos.SectionRow;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.features.lessons.service.LessonsService;
import com.ulp.features.lessons.service.SectionsService;
import com.ulp.security.Roles;
import com.ulp.security.UlpUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;

import static com.ulp.common.IConstant.*;

/**
 * Renders the lessons tab page (sidebar of sections + content list).
 * Split out of {@link SectionsController} so the entry-point view is
 * isolated from section CRUD form handling.
 */
@Controller
@RequestMapping("/lecturer/classes/{classId}/lessons")
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class LessonsTabController {

    private static final String VIEW_LESSONS = "classes/detail-lessons";
    private static final String ATTR_SECTIONS            = "sections";
    private static final String ATTR_CAN_EDIT            = "canEdit";
    private static final String ATTR_SELECTED_SECTION_ID = "selectedSectionId";
    private static final String ATTR_SELECTED_SECTION    = "selectedSection";

    private final SectionsService sectionsService;
    private final LessonsService lessonsService;
    private final ClassesService classesService;
    private final SectionRepository sectionRepository;

    public LessonsTabController(SectionsService sectionsService,
                                LessonsService lessonsService,
                                ClassesService classesService,
                                SectionRepository sectionRepository) {
        this.sectionsService = sectionsService;
        this.lessonsService = lessonsService;
        this.classesService = classesService;
        this.sectionRepository = sectionRepository;
    }

    /**
     * Renders the lessons tab page with the current section list.
     *
     * <p>The optional {@code section} query parameter selects a folder in
     * the left column. When present the section is validated to belong to
     * {@code classId}; if it doesn't (stale link, bad URL), we silently
     * fall back to "all lessons" instead of returning 404 so the UX stays
     * soft.
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

        // Soft validation: cross-class links degrade gracefully instead of 404.
        Section selectedSection = null;
        if (section != null) {
            selectedSection = sectionRepository.findByIdAndClassId(section, classId)
                    .orElse(null);
        }
        Long selectedSectionId = selectedSection != null ? selectedSection.getId() : null;

        // Lessons fetched only when a section is selected so the "All
        // lessons" landing page stays cheap.
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
}
